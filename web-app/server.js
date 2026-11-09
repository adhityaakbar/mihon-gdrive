const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { execSync, spawn } = require('child_process');

const PORT = process.env.PORT || 5050;
const CACHE_DIR = process.env.CACHE_DIR || path.join(__dirname, 'cache');
const PUBLIC_DIR = path.join(__dirname, 'public');

if (!fs.existsSync(CACHE_DIR)) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
}
const EXTRACTED_DIR = path.join(CACHE_DIR, 'extracted');
if (!fs.existsSync(EXTRACTED_DIR)) {
  fs.mkdirSync(EXTRACTED_DIR, { recursive: true });
}

const DEFAULT_KEY = "AIzaSyAWGrfCCr7albM3lmCc937gx4uIphbpeKQ";
const DEFAULT_FOLDER = "1m6tcNvSysd0kE2x-33dxGtkXlXVJsYMB";
const USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

// In-memory cache for catalog & chapters (TTL: 30 minutes)
const memoryCache = new Map();
// Cache for extracted images list: fileId -> string[] of file paths
const extractedPagesCache = new Map();
// Real-time download/extraction progress tracker
const progressMap = new Map();

function getCached(key) {
  const item = memoryCache.get(key);
  if (!item) return null;
  if (Date.now() > item.expiry) {
    memoryCache.delete(key);
    return null;
  }
  return item.value;
}

function setCache(key, value, ttlMs = 30 * 60 * 1000) {
  memoryCache.set(key, { value, expiry: Date.now() + ttlMs });
}

function naturalSort(a, b) {
  return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
}

function cleanName(name) {
  return name.replace(/\.(pdf|cbz|cbr|zip|rar)$/i, '')
             .replace(/^(?:Salinan|Copy of)\s+/i, '')
             .trim();
}

function isComicFile(name, mimeType = '') {
  const lower = name.toLowerCase();
  if (/\.(pdf|cbz|cbr|zip|rar)$/i.test(lower)) return true;
  if (/pdf|zip|rar|cbr|cbz/i.test(mimeType)) return true;
  return false;
}

function isArchiveFile(name) {
  return /\.(cbz|cbr|zip|rar)$/i.test(name);
}

// Recursively find all image files in a directory
function findImagesRecursively(dir) {
  let results = [];
  if (!fs.existsSync(dir)) return results;

  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results = results.concat(findImagesRecursively(fullPath));
    } else if (/\.(jpe?g|png|webp|gif|bmp|avif)$/i.test(entry.name)) {
      results.push(fullPath);
    }
  }
  return results;
}

// Fetch items inside folder using Google Drive API v3
async function fetchFolderContents(folderId, apiKey = DEFAULT_KEY) {
  const cacheKey = `folder_${folderId}`;
  const cached = getCached(cacheKey);
  if (cached) return cached;

  const results = [];
  let pageToken = null;

  try {
    do {
      const tokenParam = pageToken ? `&pageToken=${encodeURIComponent(pageToken)}` : '';
      const apiUrl = `https://www.googleapis.com/drive/v3/files?q='${folderId}'+in+parents+and+trashed=false&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true&fields=nextPageToken,files(id,name,mimeType,thumbnailLink,size)&key=${apiKey}${tokenParam}`;

      const res = await fetch(apiUrl, {
        headers: { 'User-Agent': USER_AGENT }
      });

      if (!res.ok) {
        console.error(`Drive API error ${res.status}: ${await res.text()}`);
        break;
      }

      const data = await res.json();
      if (data.files && Array.isArray(data.files)) {
        for (const file of data.files) {
          results.push({
            id: file.id,
            name: file.name,
            mimeType: file.mimeType,
            isFolder: file.mimeType === 'application/vnd.google-apps.folder',
            thumbnailLink: file.thumbnailLink || null,
            size: file.size ? parseInt(file.size, 10) : 0
          });
        }
      }
      pageToken = data.nextPageToken;
    } while (pageToken);
  } catch (err) {
    console.error(`Failed to fetch folder ${folderId}:`, err);
  }

  setCache(cacheKey, results);
  return results;
}

// Direct file download URL from Google Drive
async function getFileDownloadUrl(fileId) {
  let downloadUrl = `https://drive.google.com/uc?export=download&id=${fileId}`;
  const res = await fetch(downloadUrl, {
    headers: { 'User-Agent': USER_AGENT },
    redirect: 'manual'
  });

  const location = res.headers.get('location');
  if (location) {
    return location;
  }

  // Check virus warning html
  const text = await res.text();
  const confirmMatch = text.match(/[?&]confirm=([a-zA-Z0-9_-]+)/);
  const confirmToken = confirmMatch ? confirmMatch[1] : 't';
  return `https://drive.usercontent.google.com/download?id=${fileId}&export=download&confirm=${confirmToken}`;
}

// Ensure a file is downloaded and cached locally with progress tracking
async function ensureLocalFile(fileId, fileName = 'file.bin') {
  const ext = fileName.split('.').pop().toLowerCase();
  const cacheFile = path.join(CACHE_DIR, `${fileId}.${ext}`);

  if (fs.existsSync(cacheFile) && fs.statSync(cacheFile).size > 0) {
    const fileSize = fs.statSync(cacheFile).size;
    progressMap.set(fileId, {
      status: 'ready',
      percent: 100,
      loaded: fileSize,
      total: fileSize,
      message: 'File sudah tersedia di cache'
    });
    return cacheFile;
  }

  progressMap.set(fileId, {
    status: 'downloading',
    percent: 0,
    loaded: 0,
    total: 0,
    message: 'Memulai pengunduhan dari Google Drive...'
  });

  console.log(`Downloading ${fileId} (${fileName}) to cache...`);
  const directUrl = await getFileDownloadUrl(fileId);
  const res = await fetch(directUrl, {
    headers: { 'User-Agent': USER_AGENT }
  });

  if (!res.ok) {
    progressMap.set(fileId, {
      status: 'error',
      percent: 0,
      message: `Download gagal (HTTP ${res.status})`
    });
    throw new Error(`Download failed with status ${res.status}`);
  }

  const contentLength = parseInt(res.headers.get('content-length') || '0', 10);
  let loaded = 0;
  const writeStream = fs.createWriteStream(cacheFile);

  try {
    for await (const chunk of res.body) {
      writeStream.write(chunk);
      loaded += chunk.length;
      const pct = contentLength > 0 ? Math.min(99, Math.round((loaded / contentLength) * 100)) : 50;
      progressMap.set(fileId, {
        status: 'downloading',
        percent: pct,
        loaded,
        total: contentLength,
        message: `Mengunduh: ${pct}% (${(loaded / (1024*1024)).toFixed(1)} MB)`
      });
    }

    await new Promise((resolve, reject) => {
      writeStream.end();
      writeStream.on('finish', resolve);
      writeStream.on('error', reject);
    });

    progressMap.set(fileId, {
      status: 'downloaded',
      percent: 100,
      loaded,
      total: loaded,
      message: 'Pengunduhan selesai'
    });
    console.log(`Saved ${fileId} (${(fs.statSync(cacheFile).size / (1024 * 1024)).toFixed(1)} MB)`);
    return cacheFile;
  } catch (err) {
    try { fs.unlinkSync(cacheFile); } catch (_) {}
    progressMap.set(fileId, {
      status: 'error',
      percent: 0,
      message: `Download terputus: ${err.message}`
    });
    throw err;
  }
}

// Unpack archive and get sorted list of image file paths
async function getExtractedImages(fileId, fileName) {
  if (extractedPagesCache.has(fileId)) {
    const cached = extractedPagesCache.get(fileId);
    if (cached && cached.length > 0) {
      progressMap.set(fileId, { status: 'ready', percent: 100, message: 'Siap' });
      return cached;
    }
  }

  const targetDir = path.join(EXTRACTED_DIR, fileId);

  // If already extracted
  if (fs.existsSync(targetDir)) {
    const existingImages = findImagesRecursively(targetDir);
    if (existingImages.length > 0) {
      existingImages.sort((a, b) => naturalSort(path.basename(a), path.basename(b)));
      extractedPagesCache.set(fileId, existingImages);
      progressMap.set(fileId, { status: 'ready', percent: 100, message: 'Siap' });
      return existingImages;
    }
  }

  // Ensure archive file is downloaded (with real-time progress)
  const archivePath = await ensureLocalFile(fileId, fileName);
  fs.mkdirSync(targetDir, { recursive: true });

  progressMap.set(fileId, {
    status: 'extracting',
    percent: 92,
    message: 'Mengekstrak lembaran gambar komik...'
  });

  console.log(`Extracting archive ${fileName} to ${targetDir}...`);
  try {
    // Try unar first (handles rar, cbr, zip, cbz, 7z)
    execSync(`unar -q -f -o "${targetDir}" "${archivePath}"`, { timeout: 120000 });
  } catch (errUnar) {
    console.warn('unar failed, trying unrar / unzip...', errUnar.message);
    const ext = fileName.split('.').pop().toLowerCase();
    if (ext === 'cbr' || ext === 'rar') {
      execSync(`unrar x -o+ -inul "${archivePath}" "${targetDir}/"`, { timeout: 120000 });
    } else {
      execSync(`unzip -q -o "${archivePath}" -d "${targetDir}"`, { timeout: 120000 });
    }
  }

  const images = findImagesRecursively(targetDir);
  images.sort((a, b) => naturalSort(path.basename(a), path.basename(b)));
  console.log(`Extracted ${images.length} images from ${fileName}`);

  extractedPagesCache.set(fileId, images);
  progressMap.set(fileId, {
    status: 'ready',
    percent: 100,
    totalImages: images.length,
    message: `${images.length} lembar halaman siap dibaca`
  });
  return images;
}

const server = http.createServer(async (req, res) => {
  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = parsedUrl.pathname;
  const searchParams = parsedUrl.searchParams;

  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Range');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // --- API: List Comics ---
  if (pathname === '/api/comics') {
    try {
      const folderId = searchParams.get('folderId') || DEFAULT_FOLDER;
      if (searchParams.get('refresh')) {
        memoryCache.delete(`folder_${folderId}`);
      }

      const items = await fetchFolderContents(folderId);
      const subfolders = items.filter(it => it.isFolder);

      let comics = [];
      if (subfolders.length > 0) {
        comics = subfolders.map(sub => ({
          id: sub.id,
          title: cleanName(sub.name),
          cover: `/api/cover/${sub.id}?comicFolder=1`,
          format: 'folder'
        })).sort((a, b) => naturalSort(a.title, b.title));
      } else {
        const comicFiles = items.filter(it => !it.isFolder && isComicFile(it.name, it.mimeType));
        comics = [{
          id: folderId,
          title: 'Google Drive Comic',
          cover: comicFiles[0] ? `/api/cover/${comicFiles[0].id}` : null,
          format: 'single'
        }];
      }

      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({
        success: true,
        folderId,
        total: comics.length,
        comics
      }));
    } catch (err) {
      console.error('/api/comics error:', err);
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: false, error: err.message }));
    }
    return;
  }

  // --- API: List Chapters for Comic ---
  if (pathname.startsWith('/api/comics/') && pathname.endsWith('/chapters')) {
    try {
      const comicId = pathname.replace('/api/comics/', '').replace('/chapters', '');
      const items = await fetchFolderContents(comicId);

      let comicFiles = items.filter(it => !it.isFolder && isComicFile(it.name, it.mimeType));

      if (comicFiles.length === 0) {
        const subfolders = items.filter(it => it.isFolder);
        for (const sub of subfolders) {
          const subItems = await fetchFolderContents(sub.id);
          comicFiles.push(...subItems.filter(it => !it.isFolder && isComicFile(it.name, it.mimeType)));
        }
      }

      const chapters = comicFiles.map(f => {
        const ext = f.name.split('.').pop().toLowerCase();
        return {
          id: f.id,
          name: cleanName(f.name),
          fullName: f.name,
          extension: ext,
          size: f.size,
          thumbnail: f.thumbnailLink || `/api/cover/${f.id}`,
          streamUrl: `/api/stream/${f.id}?name=${encodeURIComponent(f.name)}`
        };
      }).sort((a, b) => naturalSort(a.name, b.name));

      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({
        success: true,
        comicId,
        total: chapters.length,
        chapters
      }));
    } catch (err) {
      console.error('/api/chapters error:', err);
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: false, error: err.message }));
    }
    return;
  }

  // --- API: Real-Time Download & Extraction Progress ---
  // Endpoint: /api/progress/:fileId
  if (pathname.startsWith('/api/progress/')) {
    const fileId = pathname.replace('/api/progress/', '').split('?')[0];
    const p = progressMap.get(fileId) || {
      status: 'idle',
      percent: 0,
      loaded: 0,
      total: 0,
      message: 'Menghubungkan...'
    };
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ success: true, ...p }));
    return;
  }

  // --- API: Chapter Pages Descriptor (Handles both PDF & CBR/CBZ/Archive) ---
  // Endpoint: /api/comics/:comicId/chapters/:chapterId/pages
  const chapterPagesMatch = pathname.match(/^\/api\/comics\/[^\/]+\/chapters\/([^\/]+)\/pages$/);
  if (chapterPagesMatch) {
    const chapterId = chapterPagesMatch[1];
    const fileName = searchParams.get('name') || '';
    const ext = fileName.split('.').pop().toLowerCase();

    try {
      if (ext === 'pdf' || (!isArchiveFile(fileName) && ext !== 'cbr' && ext !== 'cbz')) {
        // PDF document
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({
          success: true,
          format: 'pdf',
          chapterId,
          streamUrl: `/api/stream/${chapterId}?name=${encodeURIComponent(fileName || 'comic.pdf')}`
        }));
        return;
      }

      // Archive file (CBR, CBZ, RAR, ZIP)
      const images = await getExtractedImages(chapterId, fileName);
      const pageUrls = images.map((_, idx) => `/api/chapter/${chapterId}/page/${idx}`);

      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({
        success: true,
        format: 'images',
        chapterId,
        totalPages: images.length,
        pages: pageUrls
      }));
    } catch (err) {
      console.error(`Error loading chapter pages ${chapterId}:`, err);
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: false, error: err.message }));
    }
    return;
  }

  // --- API: Serve Extracted Archive Page Image ---
  // Endpoint: /api/chapter/:chapterId/page/:pageIndex
  const pageMatch = pathname.match(/^\/api\/chapter\/([^\/]+)\/page\/(\d+)$/);
  if (pageMatch) {
    const chapterId = pageMatch[1];
    const pageIndex = parseInt(pageMatch[2], 10);

    let images = extractedPagesCache.get(chapterId);
    if (!images || !images[pageIndex]) {
      const targetDir = path.join(EXTRACTED_DIR, chapterId);
      if (fs.existsSync(targetDir)) {
        images = findImagesRecursively(targetDir);
        images.sort((a, b) => naturalSort(path.basename(a), path.basename(b)));
        extractedPagesCache.set(chapterId, images);
      }
    }

    if (!images || !images[pageIndex] || !fs.existsSync(images[pageIndex])) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Page not found');
      return;
    }

    const imgPath = images[pageIndex];
    const ext = path.extname(imgPath).toLowerCase();
    const mimeTypes = {
      '.jpg': 'image/jpeg',
      '.jpeg': 'image/jpeg',
      '.png': 'image/png',
      '.webp': 'image/webp',
      '.gif': 'image/gif',
      '.avif': 'image/avif',
      '.bmp': 'image/bmp'
    };

    res.writeHead(200, {
      'Content-Type': mimeTypes[ext] || 'image/jpeg',
      'Cache-Control': 'public, max-age=31536000, immutable'
    });
    fs.createReadStream(imgPath).pipe(res);
    return;
  }

  // --- API: Cover Thumbnail Proxy (With CBR fallback) ---
  if (pathname.startsWith('/api/cover/')) {
    const targetId = pathname.replace('/api/cover/', '').split('?')[0];
    const isComicFolder = searchParams.get('comicFolder') === '1';

    try {
      let fileId = targetId;
      let firstFile = null;

      if (isComicFolder) {
        const items = await fetchFolderContents(targetId);
        firstFile = items.find(it => !it.isFolder && isComicFile(it.name, it.mimeType));
        if (!firstFile) {
          const sub = items.find(it => it.isFolder);
          if (sub) {
            const subItems = await fetchFolderContents(sub.id);
            firstFile = subItems.find(it => !it.isFolder && isComicFile(it.name, it.mimeType));
          }
        }
        if (firstFile) {
          fileId = firstFile.id;
        } else {
          res.writeHead(404);
          res.end('No cover file');
          return;
        }
      }

      // Check if we have a locally cached cover image
      const localCover = path.join(CACHE_DIR, `cover_${fileId}.jpg`);
      if (fs.existsSync(localCover) && fs.statSync(localCover).size > 0) {
        res.writeHead(200, {
          'Content-Type': 'image/jpeg',
          'Cache-Control': 'public, max-age=86400, stale-while-revalidate=604800'
        });
        fs.createReadStream(localCover).pipe(res);
        return;
      }

      // If it's a known archive file or drive thumbnail failed, try archive extraction
      const isArchive = firstFile ? isArchiveFile(firstFile.name) : false;

      if (!isArchive) {
        const thumbUrl = `https://drive.google.com/thumbnail?id=${fileId}&sz=w600`;
        const thumbRes = await fetch(thumbUrl, {
          headers: { 'User-Agent': USER_AGENT }
        });

        if (thumbRes.ok) {
          const contentType = thumbRes.headers.get('content-type') || 'image/jpeg';
          res.writeHead(200, {
            'Content-Type': contentType,
            'Cache-Control': 'public, max-age=86400, stale-while-revalidate=604800'
          });
          const buffer = Buffer.from(await thumbRes.arrayBuffer());
          fs.writeFileSync(localCover, buffer);
          res.end(buffer);
          return;
        }
      }

      // Archive cover fallback: extract first page image
      if (firstFile && isArchiveFile(firstFile.name)) {
        try {
          const images = await getExtractedImages(firstFile.id, firstFile.name);
          if (images.length > 0 && fs.existsSync(images[0])) {
            const ext = path.extname(images[0]).toLowerCase();
            const contentType = ext === '.png' ? 'image/png' : 'image/jpeg';
            res.writeHead(200, {
              'Content-Type': contentType,
              'Cache-Control': 'public, max-age=86400, stale-while-revalidate=604800'
            });
            fs.createReadStream(images[0]).pipe(res);
            return;
          }
        } catch (e) {
          console.warn('Could not extract cover from archive:', e.message);
        }
      }

      res.writeHead(404);
      res.end('Cover not found');
    } catch (err) {
      console.error('Cover error:', err);
      res.writeHead(500);
      res.end('Error fetching cover');
    }
    return;
  }

  // --- API: Stream File with HTTP Range Support ---
  if (pathname.startsWith('/api/stream/')) {
    const fileId = pathname.replace('/api/stream/', '').split('?')[0];
    const fileName = searchParams.get('name') || 'file.pdf';
    const ext = fileName.split('.').pop().toLowerCase();

    try {
      const cacheFile = await ensureLocalFile(fileId, fileName);
      const stat = fs.statSync(cacheFile);
      const fileSize = stat.size;
      const range = req.headers.range;

      let contentType = 'application/pdf';
      if (ext === 'cbr' || ext === 'rar') contentType = 'application/x-rar';
      else if (ext === 'cbz' || ext === 'zip') contentType = 'application/zip';

      if (range) {
        const parts = range.replace(/bytes=/, "").split("-");
        const start = parseInt(parts[0], 10);
        const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
        const chunksize = (end - start) + 1;
        const fileStream = fs.createReadStream(cacheFile, { start, end });

        res.writeHead(206, {
          'Content-Range': `bytes ${start}-${end}/${fileSize}`,
          'Accept-Ranges': 'bytes',
          'Content-Length': chunksize,
          'Content-Type': contentType,
        });
        fileStream.pipe(res);
      } else {
        res.writeHead(200, {
          'Content-Length': fileSize,
          'Content-Type': contentType,
          'Accept-Ranges': 'bytes'
        });
        fs.createReadStream(cacheFile).pipe(res);
      }
    } catch (err) {
      console.error(`Stream error for ${fileId}:`, err);
      res.writeHead(500);
      res.end('Error streaming file');
    }
    return;
  }

  // --- Health Check ---
  if (pathname === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', uptime: process.uptime() }));
    return;
  }

  // --- Static Files ---
  let filePath = path.join(PUBLIC_DIR, pathname === '/' ? 'index.html' : pathname);
  if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
    filePath = path.join(PUBLIC_DIR, 'index.html');
  }

  const extname = path.extname(filePath).toLowerCase();
  const mimeTypes = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.svg': 'image/svg+xml',
    '.ico': 'image/x-icon'
  };

  const contentType = mimeTypes[extname] || 'application/octet-stream';
  res.writeHead(200, { 'Content-Type': contentType });
  fs.createReadStream(filePath).pipe(res);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Komik Web App running at http://0.0.0.0:${PORT}`);
});
