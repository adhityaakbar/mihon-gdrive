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
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    override fun toString(): String = name

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val prefContext = screen.context

        EditTextPreference(prefContext).apply {
            key = PREF_GDRIVE_LINKS
            title = "Google Drive Folder Links"
            dialogTitle = "Input Link Google Drive"
            dialogMessage = "Masukkan satu atau beberapa link Google Drive (folder atau file). Pisahkan beberapa link dengan baris baru (enter):\n\nContoh:\nhttps://drive.google.com/drive/folders/1Ht86KpWendZW5qe6IprceMgV0T86Ed8u?usp=sharing"
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
            dialogMessage = "Opsional: Masukkan Google Drive API v3 key jika diinginkan."
            summaryProvider = androidx.preference.Preference.SummaryProvider<EditTextPreference> { pref ->
                val text = pref.text
                if (text.isNullOrBlank()) "Tidak digunakan (Mode scraping publik)" else "API Key tersimpan"
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
            val (title, firstFileId) = fetchMetadata(manga.url)
            if (manga.title.isBlank() || manga.title.startsWith("Google Drive Folder")) {
                manga.title = title
            }
            if (manga.thumbnail_url.isNullOrBlank() && firstFileId != null) {
                manga.thumbnail_url = "https://drive.google.com/thumbnail?id=$firstFileId&sz=w600"
            }
            manga.description = "Komik Google Drive: ${manga.title}\nLink: https://drive.google.com/drive/folders/${manga.url}"
            manga.status = SManga.COMPLETED
            manga.initialized = true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error fetching manga details for ${manga.url}" }
        }
        manga
    }

    suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val targetId = manga.url
        // Check if single file link
        if (targetId.startsWith("file_")) {
            val realFileId = targetId.removePrefix("file_")
            return@withContext listOf(
                SChapter.create().apply {
                    name = manga.title
                    url = "$targetId/$realFileId/${URLEncoder.encode(manga.title, "UTF-8")}.pdf"
                    date_upload = System.currentTimeMillis()
                    chapter_number = 1f
                },
            )
        }

        val url = "https://drive.google.com/drive/folders/$targetId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        val response = networkHelper.client.newCall(request).execute()
        val html = response.body.string()
        val unescaped = unescapeHex(html)

        val filePattern = Regex(""""([a-zA-Z0-9_-]{25,})",\["[a-zA-Z0-9_-]+"],"([^"]+?\.(?:pdf|cbz|cbr|zip))"""")
        val matches = filePattern.findAll(unescaped).toList()

        val chapters = mutableListOf<SChapter>()
        val seenIds = mutableSetOf<String>()

        for (match in matches) {
            val fileId = match.groupValues[1]
            val fileName = match.groupValues[2]
            if (!seenIds.add(fileId)) continue

            val cleanName = fileName.replace(Regex("""\.(pdf|cbz|cbr|zip)$""", RegexOption.IGNORE_CASE), "")
            val chapter = SChapter.create().apply {
                name = cleanName
                this.url = "$targetId/$fileId/${URLEncoder.encode(fileName, "UTF-8")}"
                date_upload = System.currentTimeMillis()
                chapter_number = ChapterRecognition.parseChapterNumber(manga.title, cleanName, -1.0).toFloat()
            }
            chapters.add(chapter)
        }

        // If no chapters matched with strict pattern, try fallback data-id and file name regex
        if (chapters.isEmpty()) {
            val fallbackPattern = Regex("""data-id="([a-zA-Z0-9_-]{25,})"[^>]*?aria-label="([^"]+?\.(?:pdf|cbz|cbr|zip))""")
            for (match in fallbackPattern.findAll(html)) {
                val fileId = match.groupValues[1]
                val fileName = match.groupValues[2].split(" ").firstOrNull() ?: match.groupValues[2]
                if (!seenIds.add(fileId)) continue
                val cleanName = fileName.replace(Regex("""\.(pdf|cbz|cbr|zip)$""", RegexOption.IGNORE_CASE), "")
                chapters.add(
                    SChapter.create().apply {
                        name = cleanName
                        this.url = "$targetId/$fileId/${URLEncoder.encode(fileName, "UTF-8")}"
                        date_upload = System.currentTimeMillis()
                        chapter_number = ChapterRecognition.parseChapterNumber(manga.title, cleanName, -1.0).toFloat()
                    },
                )
            }
        }

        // Sort descending: highest volume / chapter first
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

        val pdfFile = getOrDownloadPdf(fileId, fileName)
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val count = renderer.pageCount
        renderer.close()
        pfd.close()

        (0 until count).map { index ->
            Page(index, imageUrl = "https://gdrive.internal/$fileId/$index")
        }
    }

    override val client: okhttp3.OkHttpClient by lazy {
        networkHelper.client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (url.startsWith("https://gdrive.internal/")) {
                    val parts = url.removePrefix("https://gdrive.internal/").split("/")
                    val fileId = parts.getOrNull(0) ?: error("Invalid image url: $url")
                    val pageIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0

                    val pdfFile = kotlinx.coroutines.runBlocking { getOrDownloadPdf(fileId, "chapter.pdf") }
                    val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
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
                    val bytes = stream.toByteArray()

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

    suspend fun getOrDownloadPdf(fileId: String, fileName: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "gdrive_pdf").apply { mkdirs() }
        val cacheFile = File(dir, "$fileId.pdf")
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
            error("Failed to download PDF from Google Drive")
        }

        cacheFile
    }

    private suspend fun loadMangaFromLinks(): List<SManga> {
        val prefs = sourcePreferences()
        val rawLinks = prefs.getString(PREF_GDRIVE_LINKS, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_LINKS
        val lines = rawLinks.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        val list = mutableListOf<SManga>()
        for (line in lines) {
            val target = extractTarget(line) ?: continue
            try {
                val (title, firstFileId) = fetchMetadata(target)
                val manga = SManga.create().apply {
                    url = target
                    this.title = title
                    thumbnail_url = firstFileId?.let { "https://drive.google.com/thumbnail?id=$it&sz=w600" }
                    status = SManga.COMPLETED
                    initialized = true
                    description = "Komik Google Drive: $title\nLink: $line"
                }
                list.add(manga)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to fetch metadata for $line" }
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
        return list
    }

    private suspend fun fetchMetadata(target: String): Pair<String, String?> {
        if (target.startsWith("file_")) {
            val fileId = target.removePrefix("file_")
            return Pair("Google Drive File", fileId)
        }

        val url = "https://drive.google.com/drive/folders/$target"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        val response = networkHelper.client.newCall(request).execute()
        val html = response.body.string()

        // Parse Title: <title>Doraemon - Google Drive</title>
        val titleMatch = Regex("""<title>(.*?)(?:\s*-\s*Google Drive)?</title>""").find(html)
        var title = titleMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (title.isBlank() || title.equals("Google Drive", ignoreCase = true)) {
            title = "Komik GDrive ($target)"
        }

        // Parse first file ID for thumbnail
        val unescaped = unescapeHex(html)
        val filePattern = Regex(""""([a-zA-Z0-9_-]{25,})",\["[a-zA-Z0-9_-]+"],"([^"]+?\.(?:pdf|cbz|cbr|zip))"""")
        val firstMatch = filePattern.find(unescaped)
        val firstFileId = firstMatch?.groupValues?.getOrNull(1)

        return Pair(title, firstFileId)
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
        const val DEFAULT_LINKS = "https://drive.google.com/drive/folders/1Ht86KpWendZW5qe6IprceMgV0T86Ed8u?usp=sharing"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

fun Source.isGDrive(): Boolean = id == GDriveSource.ID
fun DomainSource.isGDrive(): Boolean = id == GDriveSource.ID
fun Manga.isGDrive(): Boolean = source == GDriveSource.ID
