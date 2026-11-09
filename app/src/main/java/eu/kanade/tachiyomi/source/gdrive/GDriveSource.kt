package eu.kanade.tachiyomi.source.gdrive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import tachiyomi.domain.source.model.Source as DomainSource

@Inject
@SingleIn(AppScope::class)
class GDriveSource(
    private val context: Context,
    private val networkHelper: NetworkHelper,
) : HttpSource(), ConfigurableSource, UnmeteredSource {

    override val id: Long = ID
    override val name: String = "Google Drive"
    override val lang: String = "all"
    override val baseUrl: String = "https://drive.google.com"
    override val supportsLatest: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    override fun toString(): String = name

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val prefContext = screen.context

        EditTextPreference(prefContext).apply {
            key = PREF_GDRIVE_LINKS
            title = "Google Drive Folder Links"
            dialogTitle = "Input Link Google Drive"
            dialogMessage = "Masukkan satu atau beberapa link Google Drive (folder induk, folder komik, atau file). Pisahkan beberapa link dengan baris baru (enter):\n\nContoh:\nhttps://drive.google.com/drive/folders/1m6tcNvSysd0kE2x-33dxGtkXlXVJsYMB?usp=sharing"
            setDefaultValue(DEFAULT_LINKS)
            summaryProvider = androidx.preference.Preference.SummaryProvider<EditTextPreference> { pref ->
                val text = pref.text
                if (text.isNullOrBlank()) {
                    "Belum ada link yang dimasukkan. Klik untuk menambahkan link Google Drive."
                } else {
                    val count = text.lineSequence().filter { it.isNotBlank() }.count()
                    "$count link Google Drive aktif."
                }
            }
            screen.addPreference(this)
        }

        EditTextPreference(prefContext).apply {
            key = PREF_GDRIVE_API_KEY
            title = "Google Drive API Key (Opsional)"
            dialogTitle = "Google Drive API Key"
            dialogMessage = "Opsional: Masukkan Google Drive API v3 key pribadi jika ada limit atau ingin lebih cepat."
            summaryProvider = androidx.preference.Preference.SummaryProvider<EditTextPreference> { pref ->
                val text = pref.text
                if (text.isNullOrBlank()) "Menggunakan kunci bawaan" else "API Key kustom tersimpan"
            }
            screen.addPreference(this)
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangasPage(emptyList(), false)
        val mangas = loadMangaFromLinks()
        MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangasPage(emptyList(), false)
        val all = loadMangaFromLinks()
        val filtered = if (query.isBlank()) {
            all
        } else {
            all.filter { it.title.contains(query, ignoreCase = true) }
        }
        MangasPage(filtered, false)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val updatedManga = if (fetchDetails) getMangaDetails(manga) else manga
        val updatedChapters = if (fetchChapters) getChapterList(manga) else chapters
        SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val targetId = manga.url
            if (targetId.startsWith("file_")) {
                val fileId = targetId.removePrefix("file_")
                if (manga.thumbnail_url.isNullOrBlank()) {
                    manga.thumbnail_url = "https://drive.google.com/thumbnail?id=$fileId&sz=w600"
                }
                manga.status = SManga.COMPLETED
                manga.initialized = true
                return@withContext manga
            }

            // Find first comic file for cover thumbnail if not already present
            if (manga.thumbnail_url.isNullOrBlank()) {
                val items = fetchFolderContents(targetId)
                var firstFile = items.firstOrNull { !it.isFolder && isComicFile(it.name, it.mimeType) }
                if (firstFile == null) {
                    val sub = items.firstOrNull { it.isFolder }
                    if (sub != null) {
                        val subItems = fetchFolderContents(sub.id)
                        firstFile = subItems.firstOrNull { !it.isFolder && isComicFile(it.name, it.mimeType) }
                    }
                }
                if (firstFile != null) {
                    manga.thumbnail_url = firstFile.thumbnailLink
                        ?: "https://drive.google.com/thumbnail?id=${firstFile.id}&sz=w600"
                }
            }

            manga.description = "Komik Google Drive: ${manga.title}\nFolder ID: $targetId"
            manga.status = SManga.COMPLETED
            manga.initialized = true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error fetching manga details for ${manga.url}" }
        }
        manga
    }

    suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val targetId = manga.url
        if (targetId.startsWith("file_")) {
            val realFileId = targetId.removePrefix("file_")
            return@withContext listOf(
                SChapter.create().apply {
                    name = cleanChapterName(manga.title)
                    url = "$targetId/$realFileId/${URLEncoder.encode(manga.title, "UTF-8")}.pdf"
                    date_upload = System.currentTimeMillis()
                    chapter_number = 1f
                },
            )
        }

        val items = fetchFolderContents(targetId)
        val comicFiles = items.filter { !it.isFolder && isComicFile(it.name, it.mimeType) }.toMutableList()

        // If folder has no comic files directly, check nested subfolders (e.g. KUNGFU BOY -> kungfu boy)
        if (comicFiles.isEmpty()) {
            val subfolders = items.filter { it.isFolder }
            for (sub in subfolders) {
                val subItems = fetchFolderContents(sub.id)
                val subFiles = subItems.filter { !it.isFolder && isComicFile(it.name, it.mimeType) }
                comicFiles.addAll(subFiles)
            }
        }

        val chapters = mutableListOf<SChapter>()
        val seenIds = mutableSetOf<String>()

        for (file in comicFiles) {
            if (!seenIds.add(file.id)) continue
            val cleanName = cleanChapterName(file.name)
            chapters.add(
                SChapter.create().apply {
                    name = cleanName
                    this.url = "$targetId/${file.id}/${URLEncoder.encode(file.name, "UTF-8")}"
                    date_upload = System.currentTimeMillis()
                    chapter_number = ChapterRecognition.parseChapterNumber(manga.title, cleanName, -1.0).toFloat()
                },
            )
        }

        // Descending order: highest chapter first
        chapters.sortedWith { c1, c2 ->
            c2.name.compareToCaseInsensitiveNaturalOrder(c1.name)
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val parts = chapter.url.split("/")
        val fileId = if (parts.size >= 2) parts[1] else chapter.url
        val fileName = if (parts.size >= 3) {
            try {
                URLDecoder.decode(parts[2], "UTF-8")
            } catch (_: Exception) {
                parts[2]
            }
        } else {
            "chapter.pdf"
        }

        val file = getOrDownloadFile(fileId, fileName)
        if (isPdfFile(file, fileName)) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            renderer.close()
            pfd.close()

            (0 until count).map { index ->
                Page(index, imageUrl = "https://gdrive.internal/$fileId/$index/${URLEncoder.encode(fileName, "UTF-8")}")
            }
        } else {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val reader = ArchiveReader(pfd)
            val entries = reader.useEntries { entriesSequence ->
                entriesSequence
                    .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                    .map { it.name }
                    .toList()
            }
            reader.close()
            pfd.close()

            entries.mapIndexed { index, entryName ->
                Page(index, imageUrl = "https://gdrive.internal/$fileId/$index/${URLEncoder.encode(fileName, "UTF-8")}?entry=${URLEncoder.encode(entryName, "UTF-8")}")
            }
        }
    }

    override val client: okhttp3.OkHttpClient by lazy {
        networkHelper.client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (url.startsWith("https://gdrive.internal/")) {
                    val httpUrl = request.url
                    val pathSegments = httpUrl.pathSegments
                    val fileId = pathSegments.getOrNull(0) ?: error("Invalid image url: $url")
                    val pageIndex = pathSegments.getOrNull(1)?.toIntOrNull() ?: 0
                    val fileName = pathSegments.getOrNull(2)?.let {
                        try {
                            URLDecoder.decode(it, "UTF-8")
                        } catch (_: Exception) {
                            it
                        }
                    } ?: "chapter.pdf"
                    val entryName = httpUrl.queryParameter("entry")

                    val file = kotlinx.coroutines.runBlocking { getOrDownloadFile(fileId, fileName) }
                    val bytes = if (entryName != null) {
                        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        val reader = ArchiveReader(pfd)
                        val stream = reader.getInputStream(entryName) ?: error("Entry $entryName not found in archive")
                        val data = stream.readBytes()
                        reader.close()
                        pfd.close()
                        data
                    } else {
                        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = PdfRenderer(pfd)
                        val pdfPage = renderer.openPage(pageIndex)
                        val width = pdfPage.width * 2
                        val height = pdfPage.height * 2
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pdfPage.close()
                        renderer.close()
                        pfd.close()

                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                        bitmap.recycle()
                        stream.toByteArray()
                    }

                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(bytes.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                } else {
                    chain.proceed(request)
                }
            }
            .build()
    }

    suspend fun getOrDownloadFile(fileId: String, fileName: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "gdrive_cache").apply { mkdirs() }
        val ext = fileName.substringAfterLast(".", "pdf")
        val cacheFile = File(dir, "$fileId.$ext")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return@withContext cacheFile
        }

        val tempFile = File(dir, "$fileId.tmp")
        if (tempFile.exists()) tempFile.delete()

        val downloadUrl = "https://drive.google.com/uc?export=download&id=$fileId"
        var request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        var response = networkHelper.client.newCall(request).execute()

        // Handle Google Drive virus scan warning for large files
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.contains("text/html", ignoreCase = true)) {
            val html = response.body.string()
            val confirmMatch = Regex("""[?&]confirm=([a-zA-Z0-9_-]+)""").find(html)
            val confirmToken = confirmMatch?.groupValues?.getOrNull(1) ?: "t"
            val confirmUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=$confirmToken"
            request = Request.Builder()
                .url(confirmUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            response = networkHelper.client.newCall(request).execute()
        }

        response.body.byteStream().use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (tempFile.length() > 0) {
            tempFile.renameTo(cacheFile)
        } else {
            tempFile.delete()
            error("Failed to download file from Google Drive")
        }

        cacheFile
    }

    suspend fun getOrDownloadPdf(fileId: String, fileName: String): File = getOrDownloadFile(fileId, fileName)

    private suspend fun loadMangaFromLinks(): List<SManga> {
        val prefs = sourcePreferences()
        val rawPref = prefs.getString(PREF_GDRIVE_LINKS, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_LINKS
        val rawLinks = if (rawPref == OLD_DEFAULT_LINKS) DEFAULT_LINKS else rawPref
        val lines = rawLinks.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        val list = mutableListOf<SManga>()
        val seenUrls = mutableSetOf<String>()

        for (line in lines) {
            val target = extractTarget(line) ?: continue
            if (target.startsWith("file_")) {
                if (seenUrls.add(target)) {
                    val fileId = target.removePrefix("file_")
                    list.add(
                        SManga.create().apply {
                            url = target
                            title = "Google Drive File"
                            thumbnail_url = "https://drive.google.com/thumbnail?id=$fileId&sz=w600"
                            status = SManga.COMPLETED
                            initialized = true
                            description = "File Google Drive: $line"
                        },
                    )
                }
                continue
            }

            try {
                val items = fetchFolderContents(target)
                val subfolders = items.filter { it.isFolder }
                val comicFiles = items.filter { !it.isFolder && isComicFile(it.name, it.mimeType) }

                if (subfolders.isNotEmpty()) {
                    // Container folder: each subfolder represents a manga/comic!
                    for (sub in subfolders) {
                        if (!seenUrls.add(sub.id)) continue
                        val cleanTitle = cleanComicTitle(sub.name)
                        list.add(
                            SManga.create().apply {
                                url = sub.id
                                title = cleanTitle
                                thumbnail_url = sub.thumbnailLink
                                status = SManga.COMPLETED
                                initialized = true
                                description = "Komik Google Drive: $cleanTitle"
                            },
                        )
                    }
                } else {
                    // Single comic folder with chapter files directly
                    if (seenUrls.add(target)) {
                        val folderTitle = fetchFolderName(target) ?: "Google Drive Folder ($target)"
                        val firstFile = comicFiles.firstOrNull()
                        list.add(
                            SManga.create().apply {
                                url = target
                                title = cleanComicTitle(folderTitle)
                                thumbnail_url = firstFile?.thumbnailLink
                                    ?: firstFile?.let { "https://drive.google.com/thumbnail?id=${it.id}&sz=w600" }
                                status = SManga.COMPLETED
                                initialized = true
                                description = "Komik Google Drive: $title\nLink: $line"
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load manga from $line" }
                if (seenUrls.add(target)) {
                    list.add(
                        SManga.create().apply {
                            url = target
                            title = "Google Drive Folder ($target)"
                            status = SManga.UNKNOWN
                            initialized = true
                        },
                    )
                }
            }
        }

        // Sort mangas in natural alphabetical order
        return list.sortedWith { m1, m2 ->
            m1.title.compareToCaseInsensitiveNaturalOrder(m2.title)
        }
    }

    suspend fun fetchFolderContents(folderId: String): List<GDriveItem> = withContext(Dispatchers.IO) {
        val prefs = sourcePreferences()
        val apiKey = prefs.getString(PREF_GDRIVE_API_KEY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_API_KEY

        val apiResult = fetchFromDriveApi(folderId, apiKey)
        if (apiResult.isNotEmpty()) {
            return@withContext apiResult
        }

        // Fallback to HTML scraping
        fetchFromHtmlScraping(folderId)
    }

    private fun fetchFromDriveApi(folderId: String, apiKey: String): List<GDriveItem> {
        val results = mutableListOf<GDriveItem>()
        var pageToken: String? = null

        do {
            val tokenParam = if (pageToken != null) "&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}" else ""
            val url = "https://www.googleapis.com/drive/v3/files?q=%27$folderId%27+in+parents+and+trashed=false" +
                "&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                "&fields=nextPageToken,files(id,name,mimeType,thumbnailLink)&key=$apiKey$tokenParam"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = try {
                networkHelper.client.newCall(request).execute()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Google Drive API request failed for $folderId" }
                return emptyList()
            }

            if (!response.isSuccessful) {
                logcat(LogPriority.WARN) { "Google Drive API returned HTTP ${response.code} for $folderId" }
                response.close()
                return emptyList()
            }

            val body = response.body.string()
            val parsed = try {
                json.decodeFromString<DriveFileListResponse>(body)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to parse Google Drive API response for $folderId" }
                null
            } ?: break

            for (file in parsed.files) {
                val isFolder = file.mimeType == "application/vnd.google-apps.folder"
                results.add(
                    GDriveItem(
                        id = file.id,
                        name = file.name,
                        isFolder = isFolder,
                        mimeType = file.mimeType,
                        thumbnailLink = file.thumbnailLink,
                    ),
                )
            }

            pageToken = parsed.nextPageToken
        } while (!pageToken.isNullOrBlank())

        return results
    }

    private fun fetchFromHtmlScraping(folderId: String): List<GDriveItem> {
        val url = "https://drive.google.com/drive/folders/$folderId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        val response = try {
            networkHelper.client.newCall(request).execute()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to scrape HTML for $folderId" }
            return emptyList()
        }

        val html = response.body.string()
        val unescaped = unescapeHex(html)

        val results = mutableListOf<GDriveItem>()
        val seenIds = mutableSetOf<String>()

        val pattern = Regex(""""([a-zA-Z0-9_-]{25,})",\["[a-zA-Z0-9_-]+"],"([^"]+?)"""")
        for (match in pattern.findAll(unescaped)) {
            val fileId = match.groupValues[1]
            val fileName = match.groupValues[2]
            if (!seenIds.add(fileId)) continue

            val isFolder = !fileName.contains(".") || unescaped.contains("\"$fileId\",null,null,null,\"application/vnd.google-apps.folder\"")
            val mimeType = if (isFolder) "application/vnd.google-apps.folder" else "application/octet-stream"
            results.add(GDriveItem(fileId, fileName, isFolder, mimeType))
        }

        return results
    }

    private fun fetchFolderName(targetId: String): String? {
        val url = "https://drive.google.com/drive/folders/$targetId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        return try {
            val response = networkHelper.client.newCall(request).execute()
            val html = response.body.string()
            val titleMatch = Regex("""<title>(.*?)(?:\s*-\s*Google Drive)?</title>""").find(html)
            titleMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf {
                it.isNotBlank() && !it.equals("Google Drive", ignoreCase = true)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isComicFile(name: String, mimeType: String?): Boolean {
        val lower = name.lowercase()
        if (lower.endsWith(".pdf") || lower.endsWith(".cbz") || lower.endsWith(".cbr") || lower.endsWith(".zip") || lower.endsWith(".rar")) {
            return true
        }
        if (mimeType == "application/pdf" || mimeType == "application/zip" || mimeType == "application/x-rar" || mimeType == "application/x-cbr" || mimeType == "application/x-cbz") {
            return true
        }
        return false
    }

    private fun isPdfFile(file: File, fileName: String): Boolean {
        if (fileName.endsWith(".pdf", ignoreCase = true)) return true
        if (fileName.endsWith(".cbz", ignoreCase = true) ||
            fileName.endsWith(".cbr", ignoreCase = true) ||
            fileName.endsWith(".zip", ignoreCase = true) ||
            fileName.endsWith(".rar", ignoreCase = true)) {
            return false
        }
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                read >= 4 && header[0] == '%'.code.toByte() && header[1] == 'P'.code.toByte() && header[2] == 'D'.code.toByte() && header[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanChapterName(raw: String): String {
        return raw.replace(Regex("""\.(pdf|cbz|cbr|zip|rar)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:Salinan|Copy of)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanComicTitle(raw: String): String {
        return raw.replace(Regex("""^(?:Salinan|Copy of)\s+""", RegexOption.IGNORE_CASE), "").trim()
    }

    private fun extractTarget(input: String): String? {
        val trimmed = input.trim()
        val folderMatch = Regex("""(?:folders/|id=)([a-zA-Z0-9_-]{15,})""").find(trimmed)
        if (folderMatch != null) return folderMatch.groupValues[1]

        val fileMatch = Regex("""file/d/([a-zA-Z0-9_-]{15,})""").find(trimmed)
        if (fileMatch != null) return "file_${fileMatch.groupValues[1]}"

        if (trimmed.matches(Regex("""[a-zA-Z0-9_-]{25,}"""))) return trimmed
        return null
    }

    private fun unescapeHex(input: String): String {
        return Regex("""\\x([0-9a-fA-F]{2})""").replace(input) { match ->
            val code = match.groupValues[1].toInt(16)
            code.toChar().toString()
        }
    }

    companion object {
        const val ID = 1000L
        const val PREF_GDRIVE_LINKS = "gdrive_links"
        const val PREF_GDRIVE_API_KEY = "gdrive_api_key"
        const val DEFAULT_LINKS = "https://drive.google.com/drive/folders/1m6tcNvSysd0kE2x-33dxGtkXlXVJsYMB?usp=sharing"
        const val OLD_DEFAULT_LINKS = "https://drive.google.com/drive/folders/1Ht86KpWendZW5qe6IprceMgV0T86Ed8u?usp=sharing"
        const val DEFAULT_API_KEY = "AIzaSyAWGrfCCr7albM3lmCc937gx4uIphbpeKQ"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

data class GDriveItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val mimeType: String,
    val thumbnailLink: String? = null,
)

@Serializable
data class DriveFileListResponse(
    val nextPageToken: String? = null,
    val files: List<DriveFileItem> = emptyList(),
)

@Serializable
data class DriveFileItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val thumbnailLink: String? = null,
)

fun Source.isGDrive(): Boolean = id == GDriveSource.ID
fun DomainSource.isGDrive(): Boolean = id == GDriveSource.ID
fun Manga.isGDrive(): Boolean = source == GDriveSource.ID
