package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal class PdfPageLoader(
    private val file: UniFile,
    private val context: Context,
) : PageLoader() {

    override var isLocal: Boolean = true

    private var pfd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    override suspend fun getPages(): List<ReaderPage> = withContext(Dispatchers.IO) {
        val descriptor = context.contentResolver.openFileDescriptor(file.uri, "r")
            ?: error("Cannot open PDF file descriptor: ${file.uri}")
        pfd = descriptor

        val renderer = PdfRenderer(descriptor)
        pdfRenderer = renderer

        val count = renderer.pageCount
        (0 until count).map { index ->
            ReaderPage(index).apply {
                stream = {
                    renderPageToStream(index)
                }
                status = Page.State.Ready
            }
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
            pfd?.close()
        } catch (_: Exception) {}
        pdfRenderer = null
        pfd = null
    }
}
