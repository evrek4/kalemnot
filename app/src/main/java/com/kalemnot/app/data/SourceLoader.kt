package com.kalemnot.app.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/** Sayfaların arka planını (PDF sayfası veya görsel) bitmap olarak yükler. Tek iş parçacığından kullanılmalıdır. */
class SourceLoader(private val dir: File) : Closeable {
    private val renderers = HashMap<String, PdfRenderer>()
    private val fds = HashMap<String, ParcelFileDescriptor>()

    fun load(page: Page, width: Int): Bitmap? {
        val src = page.src ?: return null
        val f = File(dir, src)
        if (!f.exists()) return null
        if (page.pdfIndex < 0) return ImageUtil.decode(f, width)

        var r = renderers[src]
        if (r == null) {
            val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            r = PdfRenderer(fd)
            fds[src] = fd
            renderers[src] = r
        }
        if (page.pdfIndex >= r.pageCount) return null
        val p = r.openPage(page.pdfIndex)
        try {
            val h = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            p.close()
        }
    }

    override fun close() {
        for (r in renderers.values) {
            try { r.close() } catch (e: Exception) { }
        }
        for (fd in fds.values) {
            try { fd.close() } catch (e: Exception) { }
        }
        renderers.clear()
        fds.clear()
    }
}
