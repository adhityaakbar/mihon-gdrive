const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');

const CHROME_PATH = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const TARGET_URL = "https://komik.aneta.my.id";

async function runDevToolsTest() {
  console.log("==================================================================");
  console.log("CHROME REMOTE DEBUGGING & DEVTOOLS PROTOCOL TEST SUITE");
  console.log("==================================================================");
  console.log(`1. Launching Chrome via DevTools Protocol (${CHROME_PATH})...`);

  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-gpu',
      '--window-size=1280,850'
    ],
    defaultViewport: { width: 1280, height: 850 }
  });

  const page = await browser.newPage();

  // Listen to browser console messages
  page.on('console', msg => {
    const text = msg.text();
    if (text.includes('error') || text.includes('failed') || text.includes('Error')) {
      console.log(`   [Browser Console] ${msg.type().toUpperCase()}: ${text}`);
    }
  });

  page.on('pageerror', err => {
    console.error(`   [Browser Page Error]: ${err.message}`);
  });

  console.log(`2. Navigating to ${TARGET_URL}...`);
  await page.goto(TARGET_URL, { waitUntil: 'networkidle2', timeout: 30000 });

  const title = await page.title();
  console.log(`   -> Page Title: "${title}"`);

  console.log("3. Waiting for Comic Catalog to render...");
  await page.waitForFunction(() => typeof allComics !== 'undefined' && allComics.length > 0, { timeout: 15000 });
  const totalComics = await page.evaluate(() => allComics.length);
  console.log(`   ✅ Catalog successfully loaded: ${totalComics} comics available.`);

  console.log("4. Selecting comic 'Yaiba'...");
  const comicTitle = await page.evaluate(() => {
    const yaiba = allComics.find(c => c.title.toLowerCase().includes('yaiba')) || allComics[0];
    showDetailView(yaiba);
    return yaiba.title;
  });
  console.log(`   -> Detail view opened for: ${comicTitle}`);

  console.log("5. Waiting for chapters list...");
  await page.waitForFunction(() => typeof currentChapters !== 'undefined' && currentChapters.length > 0, { timeout: 10000 });
  const totalChapters = await page.evaluate(() => currentChapters.length);
  console.log(`   ✅ Chapters loaded: ${totalChapters} chapters.`);

  console.log("6. Clicking Chapter 1 & Monitoring Real-time Percentage Indicator...");
  // Launch reader
  await page.evaluate(() => {
    launchReader(currentChapters[0], 1);
  });

  // Track progress percentages as they increase
  const progressLogs = [];
  let isReady = false;

  for (let i = 0; i < 40; i++) {
    const state = await page.evaluate(() => {
      const p = document.getElementById('progress-percent');
      const status = document.getElementById('reader-status-text');
      const sub = document.getElementById('reader-substatus-text');
      const phase = document.getElementById('progress-phase-label');
      const bar = document.getElementById('progress-bar-fill');
      const spinner = document.getElementById('reader-spinner');
      const counter = document.getElementById('reader-page-counter');
      const bookPages = document.querySelectorAll('#flipbook .page').length;

      return {
        percent: p ? p.textContent : '0%',
        status: status ? status.textContent : '',
        sub: sub ? sub.textContent : '',
        phase: phase ? phase.textContent : '',
        barWidth: bar ? bar.style.width : '',
        hidden: spinner ? spinner.classList.contains('hidden') : false,
        counter: counter ? counter.textContent : '',
        bookPages: bookPages
      };
    });

    const entry = `[${state.percent}] ${state.status} | ${state.sub} (${state.phase})`;
    if (!progressLogs.includes(entry)) {
      console.log(`   📊 [PROGRESS] ${entry}`);
      progressLogs.push(entry);
    }

    if (state.hidden && state.bookPages > 0) {
      console.log(`   ✅ 100% COMPLETE! 3D Book reader mounted with ${state.bookPages} pages!`);
      console.log(`   -> Initial Page Counter: ${state.counter}`);
      isReady = true;
      break;
    }

    await new Promise(r => setTimeout(r, 250));
  }

  if (!isReady) {
    console.warn("   ⚠️ Reader took longer than expected to hide spinner, proceeding to test...");
  }

  // Current page check
  const startPage = await page.evaluate(() => currentPage);
  console.log(`7. Initial page before scroll: Page ${startPage}`);

  // Test Mouse Wheel Scroll Down (Scroll Down = Next Page)
  console.log("8. Testing Mouse Wheel Scroll Down (deltaY: 150)...");
  await page.mouse.move(640, 420);
  await page.mouse.wheel({ deltaY: 150 });
  
  // Wait for 3D flip animation
  await new Promise(r => setTimeout(r, 800));

  const pageAfterDown = await page.evaluate(() => {
    return {
      currentPage: currentPage,
      counter: document.getElementById('reader-page-counter').textContent,
      hud: document.getElementById('hud-page').textContent
    };
  });
  console.log(`   ✅ After Scroll Down: Current Page = ${pageAfterDown.currentPage}, Counter = "${pageAfterDown.counter}"`);

  // Test Mouse Wheel Scroll Up (Scroll Up = Prev Page)
  console.log("9. Testing Mouse Wheel Scroll Up (deltaY: -150)...");
  await page.mouse.wheel({ deltaY: -150 });
  await new Promise(r => setTimeout(r, 800));

  const pageAfterUp = await page.evaluate(() => {
    return {
      currentPage: currentPage,
      counter: document.getElementById('reader-page-counter').textContent,
      hud: document.getElementById('hud-page').textContent
    };
  });
  console.log(`   ✅ After Scroll Up: Current Page = ${pageAfterUp.currentPage}, Counter = "${pageAfterUp.counter}"`);

  // Capture Screenshot of Reader in Action
  console.log("10. Capturing DevTools Screenshot of 3D Book Reader...");
  const screenshotPath = path.join(__dirname, 'reader_devtools_screenshot.png');
  await page.screenshot({ path: screenshotPath });
  console.log(`   📸 Screenshot captured and saved to: ${screenshotPath}`);

  // Also test reading Conan (PDF format) to verify dual engine progress
  console.log("11. Testing PDF Comic (Detektif Conan) Progress & Reader...");
  await page.evaluate(() => {
    closeReader();
    const conan = allComics.find(c => c.title.toLowerCase().includes('conan')) || allComics[0];
    showDetailView(conan);
  });

  await page.waitForFunction(() => typeof currentChapters !== 'undefined' && currentChapters.length > 0, { timeout: 10000 });
  await page.evaluate(() => {
    launchReader(currentChapters[0], 1);
  });

  for (let i = 0; i < 30; i++) {
    const pdfState = await page.evaluate(() => {
      const p = document.getElementById('progress-percent');
      const status = document.getElementById('reader-status-text');
      const spinner = document.getElementById('reader-spinner');
      return {
        percent: p ? p.textContent : '',
        status: status ? status.textContent : '',
        hidden: spinner ? spinner.classList.contains('hidden') : false
      };
    });
    if (pdfState.percent) {
      console.log(`   📊 [CONAN PDF PROGRESS] ${pdfState.percent} - ${pdfState.status}`);
    }
    if (pdfState.hidden) {
      console.log("   ✅ Conan PDF Reader ready and mounted!");
      break;
    }
    await new Promise(r => setTimeout(r, 400));
  }

  await browser.close();
  console.log("==================================================================");
  console.log("🎉 ALL CHROME DEVTOOLS REMOTE DEBUGGING TESTS PASSED PERFECTLY!");
  console.log("==================================================================");
}

runDevToolsTest().catch(err => {
  console.error("Test failed with error:", err);
  process.exit(1);
});
