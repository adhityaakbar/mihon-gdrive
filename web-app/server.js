const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const url = require('url');

const PORT = process.env.PORT || 5050;
const CACHE_DIR = process.env.CACHE_DIR || path.join(__dirname, 'cache');
const PUBLIC_DIR = path.join(__dirname, 'public');

if (!fs.existsSync(CACHE_DIR)) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
}

const DEFAULT_KEY = "AIzaSyAWGrfCCr7albM3lmCc937gx4uIphbpeKQ";
const DEFAULT_FOLDER = "1m6tcNvSysd0kE2x-33dxGtkXlXVJsYMB";
const USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

// In-memory cache for catalog & chapters (TTL: 30 minutes)
const memoryCache = new Map();

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

// Download or get cached file stream
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
        // Direct files
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

      // If empty directly, check nested subfolders
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

  // --- API: Cover Thumbnail Proxy ---
  if (pathname.startsWith('/api/cover/')) {
    const targetId = pathname.replace('/api/cover/', '').split('?')[0];
    const isComicFolder = searchParams.get('comicFolder') === '1';

    try {
      let fileId = targetId;
      if (isComicFolder) {
        // Find first file in folder for cover
        const items = await fetchFolderContents(targetId);
        let first = items.find(it => !it.isFolder && isComicFile(it.name, it.mimeType));
        if (!first) {
          const sub = items.find(it => it.isFolder);
          if (sub) {
            const subItems = await fetchFolderContents(sub.id);
            first = subItems.find(it => !it.isFolder && isComicFile(it.name, it.mimeType));
          }
        }
        if (first) {
          fileId = first.id;
        } else {
          res.writeHead(404);
          res.end('No cover file');
          return;
        }
      }

      const thumbUrl = `https://drive.google.com/thumbnail?id=${fileId}&sz=w600`;
      const thumbRes = await fetch(thumbUrl, {
        headers: { 'User-Agent': USER_AGENT }
      });

      if (!thumbRes.ok) {
        res.writeHead(thumbRes.status);
        res.end();
        return;
      }

      const contentType = thumbRes.headers.get('content-type') || 'image/jpeg';
      res.writeHead(200, {
        'Content-Type': contentType,
        'Cache-Control': 'public, max-age=86400, stale-while-revalidate=604800'
      });

      const buffer = Buffer.from(await thumbRes.arrayBuffer());
      res.end(buffer);
    } catch (err) {
      res.writeHead(500);
      res.end('Error fetching cover');
    }
    return;
  }

  // --- API: Stream File with Range Support (PDF / Archive) ---
  if (pathname.startsWith('/api/stream/')) {
    const fileId = pathname.replace('/api/stream/', '').split('?')[0];
    const fileName = searchParams.get('name') || 'file.pdf';
    const ext = fileName.split('.').pop().toLowerCase();

    const cacheFile = path.join(CACHE_DIR, `${fileId}.${ext}`);

    try {
      // If already cached locally on disk
      if (fs.existsSync(cacheFile) && fs.statSync(cacheFile).size > 0) {
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
        return;
      }

      // Download file to cache in background / on-demand
      console.log(`Downloading file ${fileId} (${fileName}) to cache...`);
      const directUrl = await getFileDownloadUrl(fileId);
      const downloadRes = await fetch(directUrl, {
        headers: { 'User-Agent': USER_AGENT }
      });

      if (!downloadRes.ok) {
        res.writeHead(downloadRes.status);
        res.end(`Download error: ${downloadRes.status}`);
        return;
      }

      const arrayBuf = await downloadRes.arrayBuffer();
      const buf = Buffer.from(arrayBuf);
      fs.writeFileSync(cacheFile, buf);
      console.log(`Saved ${fileId} (${(buf.length / (1024*1024)).toFixed(1)} MB) to cache`);

      let contentType = 'application/pdf';
      if (ext === 'cbr' || ext === 'rar') contentType = 'application/x-rar';
      else if (ext === 'cbz' || ext === 'zip') contentType = 'application/zip';

      res.writeHead(200, {
        'Content-Length': buf.length,
        'Content-Type': contentType,
        'Accept-Ranges': 'bytes'
      });
      res.end(buf);
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
