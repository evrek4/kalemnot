package com.kalemnot.app.data

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.kalemnot.app.ink.PageRenderer
import java.io.File

object PdfExporter {
    private fun safeName(s: String): String {
        val t = s.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("").trim()
        return if (t.isEmpty()) "not" else t.take(60)
    }

    /** Sayfaları (çizimleriyle birlikte) tek bir PDF dosyasına yazar. Arka planda çağrılmalıdır. */
    fun export(context: Context, noteDir: File, title: String, pages: List<Page>): File {
        val outDir = File(context.cacheDir, "exports")
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }
        val out = File(outDir, safeName(title) + ".pdf")

        val doc = PdfDocument()
        val loader = SourceLoader(noteDir)
        val renderer = PageRenderer()
        try {
            pages.forEachIndexed { i, p ->
                val pw = 595
                val ph = (pw * p.height / p.width).toInt().coerceAtLeast(1)
                val info = PdfDocument.PageInfo.Builder(pw, ph, i + 1).create()
                val pg = doc.startPage(info)
                val k = pw / p.width
                pg.canvas.scale(k, k)
                val bg = try { loader.load(p, 1600) } catch (e: Throwable) { null }
                renderer.drawPage(pg.canvas, p, bg, shadow = false, useCache = false)
                bg?.recycle()
                doc.finishPage(pg)
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
            loader.close()
        }
        return out
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "PDF olarak paylaş / kaydet")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
