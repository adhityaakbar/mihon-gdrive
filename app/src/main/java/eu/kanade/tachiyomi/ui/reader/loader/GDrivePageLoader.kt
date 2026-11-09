package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import eu.kanade.tachiyomi.source.gdrive.GDriveSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.archive.ArchiveReader
import tachiyomi.core.common.util.system.ImageUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder

internal class GDrivePageLoader(
    private val chapter: ReaderChapter,
    private val source: GDriveSource,
    private val context: Context,
) : PageLoader() {

    override var isLocal: Boolean = false

    private var pfd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var archiveReader: ArchiveReader? = null

    override suspend fun getPages(): List<ReaderPage> = withContext(Dispatchers.IO) {
        val chapterUrl = chapter.chapter.url // format: folderId/fileId/fileName
        val parts = chapterUrl.split("/")
        val fileId = if (parts.size >= 2) parts[1] else chapterUrl
        val fileName = if (parts.size >= 3) {
            try {
                URLDecoder.decode(parts[2], "UTF-8")
            } catch (_: Exception) {
                parts[2]
            }
        } else {
            "chapter.pdf"
        }

        val downloadedFile = source.getOrDownloadFile(fileId, fileName)
        val descriptor = ParcelFileDescriptor.open(downloadedFile, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = descriptor

        if (isPdf(downloadedFile, fileName)) {
            val renderer = PdfRenderer(descriptor)
            pdfRenderer = renderer

            val count = renderer.pageCount
            (0 until count).map { index ->
                ReaderPage(index).apply {
                    this.chapter = this@GDrivePageLoader.chapter
                    stream = {
                        renderPageToStream(index)
                    }
                    status = Page.State.Ready
                }
            }
        } else {
            val reader = ArchiveReader(descriptor)
            archiveReader = reader

            val entries = reader.useEntries { entriesSequence ->
                entriesSequence
                    .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                    .map { it.name }
                    .toList()
            }
            entries.mapIndexed { index, entryName ->
                ReaderPage(index).apply {
                    this.chapter = this@GDrivePageLoader.chapter
                    stream = {
                        reader.getInputStream(entryName)!!
                    }
                    status = Page.State.Ready
                }
            }
        }
    }

    private fun isPdf(file: File, fileName: String): Boolean {
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

    private fun renderPageToStream(pageIndex: Int): ByteArrayInputStream {
        val renderer = pdfRenderer ?: error("PdfRenderer not initialized")
        synchronized(renderer) {
            val page = renderer.openPage(pageIndex)
            val width = page.width * 2
            val height = page.height * 2
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            bitmap.recycle()
            return ByteArrayInputStream(stream.toByteArray())
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        try {
            pdfRenderer?.close()
        } catch (_: Exception) {}
        try {
            archiveReader?.close()
        } catch (_: Exception) {}
        try {
            pfd?.close()
        } catch (_: Exception) {}
        pdfRenderer = null
        archiveReader = null
        pfd = null
    }
}
