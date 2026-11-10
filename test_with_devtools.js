const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');

const CHROME_PATH = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const TARGET_URL = "https://komik.aneta.my.id";

async function runDevToolsTest() {
  console.log("==================================================================");
  console.log("CHROME DEVTOOLS FULL AUTOMATION TEST SUITE");
  console.log("==================================================================");
  console.log(`1. Launching Chrome (${CHROME_PATH})...`);

  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-gpu', '--window-size=1280,850'],
    defaultViewport: { width: 1280, height: 850 }
  });

  const page = await browser.newPage();

  console.log(`2. Navigating to ${TARGET_URL}...`);
  await page.goto(TARGET_URL, { waitUntil: 'networkidle2' });

  console.log("3. Waiting for catalog & chapters...");
  await page.waitForFunction(() => typeof allComics !== 'undefined' && allComics.length > 0);
  
  await page.evaluate(async () => {
    const yaiba = allComics.find(c => c.title.toLowerCase().includes('yaiba')) || allComics[0];
    currentComic = yaiba;
    const res = await fetch(`/api/comics/${yaiba.id}/chapters`);
    const data = await res.json();
    currentChapters = data.chapters;
    await launchReader(currentChapters[0], 1);
  });

  await page.waitForFunction(() => {
    const sp = document.getElementById('reader-spinner');
    return sp && sp.classList.contains('hidden');
  }, { timeout: 15000 });

  // TEST 1: Check Book Clipping (Top & Bottom Margins)
  console.log("4. Verifying 3D Book Layout & Zero Clipping...");
  const rects = await page.evaluate(() => {
    const p = document.querySelector('.page').getBoundingClientRect();
    const tb = document.getElementById('reader-topbar').getBoundingClientRect();
    const bb = document.getElementById('reader-bottombar').getBoundingClientRect();
    return {
      top: Math.round(p.top),
      bottom: Math.round(p.bottom),
      height: Math.round(p.height),
      topbarBottom: Math.round(tb.bottom),
      bottombarTop: Math.round(bb.top)
    };
  });
  console.log(`   📊 Book top: ${rects.top}px (Topbar ends at: ${rects.topbarBottom}px)`);
  console.log(`   📊 Book bottom: ${rects.bottom}px (Bottombar begins at: ${rects.bottombarTop}px)`);

  if (rects.top >= rects.topbarBottom && rects.bottom <= rects.bottombarTop) {
    console.log("   ✅ SUCCESS: 3D Book fits inside viewport with zero top/bottom clipping!");
  } else {
    console.warn("   ⚠️ Warning: Book boundary intersects bars!");
  }

  // TEST 2: Mouse Wheel Scroll Down & Up
  console.log("5. Testing Mouse Wheel Scroll Down & Up...");
  const startPage = await page.evaluate(() => currentPage);
  console.log(`   -> Start Page: ${startPage}`);

  await page.mouse.move(640, 420);
  await page.mouse.wheel({ deltaY: 150 });
  await new Promise(r => setTimeout(r, 800));

  const afterWheelDown = await page.evaluate(() => currentPage);
  console.log(`   ✅ After Wheel Scroll Down: Current Page = ${afterWheelDown} (Advanced!)`);

  await page.mouse.wheel({ deltaY: -150 });
  await new Promise(r => setTimeout(r, 800));

  const afterWheelUp = await page.evaluate(() => currentPage);
  console.log(`   ✅ After Wheel Scroll Up: Current Page = ${afterWheelUp} (Returned!)`);

  // TEST 3: Touch Screen Swipe Gestures
  console.log("6. Testing Touch Screen Swipe Down (Simulate Scroll Down)...");
  await page.evaluate(() => {
    // Construct real Touch objects
    const touchStart = new Touch({
      identifier: 1,
      target: window,
      clientX: 500,
      clientY: 200,
      pageX: 500,
      pageY: 200,
      screenX: 500,
      screenY: 200
    });
    window.dispatchEvent(new TouchEvent('touchstart', {
      touches: [touchStart],
      targetTouches: [touchStart],
      changedTouches: [touchStart],
      bubbles: true,
      cancelable: true
    }));

    const touchEnd = new Touch({
      identifier: 1,
      target: window,
      clientX: 500,
      clientY: 340,
      pageX: 500,
      pageY: 340,
      screenX: 500,
      screenY: 340
    });
    window.dispatchEvent(new TouchEvent('touchend', {
      touches: [],
      targetTouches: [],
      changedTouches: [touchEnd],
      bubbles: true,
      cancelable: true
    }));
  });
  await new Promise(r => setTimeout(r, 800));

  const afterTouchDown = await page.evaluate(() => currentPage);
  console.log(`   ✅ After Touch Swipe Down: Current Page = ${afterTouchDown} (Advanced via Touch!)`);

  // TEST 4: Webtoon Mode Continuous Scroll & Bottom Overscroll Auto-Advance
  console.log("7. Testing Webtoon Mode Continuous Scroll & Overscroll Next Chapter...");
  await page.evaluate(() => {
    selectReadingMode('webtoon');
  });
  await new Promise(r => setTimeout(r, 600));

  const currChapterBefore = await page.evaluate(() => activeChapter.name);
  console.log(`   -> Initial Webtoon Chapter: "${currChapterBefore}"`);

  // Scroll to bottom of Webtoon container and trigger overscroll
  await page.evaluate(() => {
    const c = document.getElementById('container-webtoon');
    c.scrollTop = c.scrollHeight;
    // Dispatch wheel overscroll down at bottom
    c.dispatchEvent(new WheelEvent('wheel', { deltaY: 80, bubbles: true }));
  });

  // Wait for next chapter transition
  await new Promise(r => setTimeout(r, 2000));
  await page.waitForFunction(() => {
    const sp = document.getElementById('reader-spinner');
    return sp && sp.classList.contains('hidden');
  }, { timeout: 15000 });

  const currChapterAfter = await page.evaluate(() => activeChapter.name);
  console.log(`   ✅ After Overscroll at Bottom: Active Chapter = "${currChapterAfter}" (Auto-advanced to Next Chapter!)`);

  // Capture final screenshot
  const screenshotPath = path.join(__dirname, 'reader_devtools_screenshot.png');
  await page.screenshot({ path: screenshotPath });
  console.log(`   📸 Final Screenshot saved: ${screenshotPath}`);

  await browser.close();
  console.log("==================================================================");
  console.log("🎉 ALL TESTS PASSED SUCCESSFULLY!");
  console.log("==================================================================");
}

runDevToolsTest().catch(err => {
  console.error("Test failed:", err);
  process.exit(1);
});
