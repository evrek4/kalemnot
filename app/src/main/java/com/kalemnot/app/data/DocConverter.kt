package com.kalemnot.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Html
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/**
 * Word (.docx), Excel (.xlsx), PowerPoint (.pptx) ve EPUB dosyalarını PDF'e çevirir.
 * Çıktı PDF, uygulamanın normal PDF sayfa hattından geçer (üstüne yazılıp çizilebilir).
 * Bu bir yaklaşık dönüştürmedir: metin, temel biçim, görseller ve basit tablolar korunur.
 */
object DocConverter {
    enum class Kind { DOCX, XLSX, PPTX, EPUB }

    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    const val MIME_EPUB = "application/epub+zip"

    val MIMES = arrayOf(MIME_DOCX, MIME_XLSX, MIME_PPTX, MIME_EPUB)

    private const val MAX_PAGES = 400

    fun kindOf(mime: String, lowerName: String): Kind? = when {
        mime == MIME_DOCX || lowerName.endsWith(".docx") -> Kind.DOCX
        mime == MIME_XLSX || lowerName.endsWith(".xlsx") -> Kind.XLSX
        mime == MIME_PPTX || lowerName.endsWith(".pptx") -> Kind.PPTX
        mime == MIME_EPUB || lowerName.endsWith(".epub") -> Kind.EPUB
        else -> null
    }

    fun convert(ctx: Context, src: File, kind: Kind, out: File): Boolean {
        val pdf = PdfOut()
        val ok = try {
            ZipFile(src).use { zf ->
                val z = Zip(zf)
                when (kind) {
                    Kind.DOCX -> docx(ctx, z, pdf)
                    Kind.XLSX -> xlsx(z, pdf)
                    Kind.PPTX -> pptx(z, pdf)
                    Kind.EPUB -> epub(ctx, z, pdf)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            pdf.discard()
            return false
        }
        return pdf.finish(out)
    }

    // =====================================================================
    // Yardımcılar
    // =====================================================================

    private class Zip(private val z: ZipFile) {
        fun bytes(name: String): ByteArray? {
            val e = z.getEntry(name) ?: return null
            return z.getInputStream(e).use { it.readBytes() }
        }
    }

    private class PdfOut : Closeable {
        private val doc = PdfDocument()
        var n = 0

        fun full(): Boolean = n >= MAX_PAGES

        fun page(w: Int, h: Int, draw: (Canvas) -> Unit) {
            if (full()) return
            n++
            val info = PdfDocument.PageInfo.Builder(w, h, n).create()
            val p = doc.startPage(info)
            p.canvas.drawColor(Color.WHITE)
            draw(p.canvas)
            doc.finishPage(p)
        }

        fun finish(out: File): Boolean {
            if (n == 0) {
                doc.close()
                return false
            }
            out.outputStream().use { doc.writeTo(it) }
            doc.close()
            return true
        }

        fun discard() {
            doc.close()
        }

        override fun close() {
            doc.close()
        }
    }

    private fun parser(b: ByteArray): XmlPullParser {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(b), null)
        return p
    }

    private fun attr(p: XmlPullParser, name: String): String? = p.getAttributeValue(null, name)

    private fun resolve(basePath: String, rel: String): String {
        val r = Uri.decode(rel.substringBefore('#'))
        if (r.startsWith("/")) return r.trimStart('/')
        val parts = ArrayList<String>()
        for (s in basePath.substringBeforeLast('/', "").split('/')) if (s.isNotEmpty()) parts.add(s)
        for (seg in r.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun readRels(z: Zip, relsPath: String): Map<String, String> {
        val b = z.bytes(relsPath) ?: return emptyMap()
        val out = HashMap<String, String>()
        val p = parser(b)
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = attr(p, "Id")
                val t = attr(p, "Target")
                if (id != null && t != null) out[id] = t
            }
            e = p.next()
        }
        return out
    }

    private fun decodeImage(b: ByteArray, maxW: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= maxW) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return BitmapFactory.decodeByteArray(b, 0, b.size, o2)
    }

    private fun imageDrawable(ctx: Context, b: ByteArray, wantW: Float, wantH: Float, maxW: Float, maxH: Float): Drawable? {
        val bmp = decodeImage(b, (maxW * 2).toInt().coerceAtLeast(64)) ?: return null
        var w = if (wantW > 0f) wantW else bmp.width.toFloat()
        var h = if (wantH > 0f) wantH else w * bmp.height / bmp.width
        if (w > maxW) {
            h = h * maxW / w
            w = maxW
        }
        if (h > maxH) {
            w = w * maxH / h
            h = maxH
        }
        val d = BitmapDrawable(ctx.resources, bmp)
        d.setBounds(0, 0, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
        return d
    }

    private const val A4_W = 595
    private const val A4_H = 842
    private const val MARGIN = 48

    /** Metni A4 sayfalara böler ve PDF'e yazar. */
    private fun paginate(pdf: PdfOut, text: CharSequence, paint: TextPaint) {
        if (text.isEmpty()) return
        val cw = A4_W - 2 * MARGIN
        val chh = A4_H - 2 * MARGIN
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, cw)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
        val total = layout.lineCount
        var line = 0
        while (line < total && !pdf.full()) {
            val top = layout.getLineTop(line)
            var end = line
            while (end < total && (end == line || layout.getLineBottom(end) - top <= chh)) end++
            val bottom = layout.getLineBottom(end - 1)
            pdf.page(A4_W, A4_H) { c ->
                c.save()
                c.translate(MARGIN.toFloat(), MARGIN.toFloat())
                c.clipRect(0f, 0f, cw.toFloat(), (bottom - top).toFloat())
                c.translate(0f, -top.toFloat())
                layout.draw(c)
                c.restore()
            }
            line = end
        }
    }

    private fun basePaint(size: Float): TextPaint {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.BLACK
        p.textSize = size
        return p
    }

    private fun blank(drawW: Int): Drawable {
        val d = ColorDrawable(Color.TRANSPARENT)
        d.setBounds(0, 0, drawW, 1)
        return d
    }

    // =====================================================================
    // EPUB
    // =====================================================================

    private fun epub(ctx: Context, z: Zip, pdf: PdfOut) {
        val container = z.bytes("META-INF/container.xml") ?: throw IllegalStateException("container yok")
        var opfPath: String? = null
        run {
            val p = parser(container)
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT && opfPath == null) {
                if (e == XmlPullParser.START_TAG && p.name == "rootfile") opfPath = attr(p, "full-path")
                e = p.next()
            }
        }
        val opf = opfPath ?: throw IllegalStateException("opf yok")
        val opfBytes = z.bytes(opf) ?: throw IllegalStateException("opf okunamadı")

        val manifest = HashMap<String, String>()
        val spine = ArrayList<String>()
        run {
            val p = parser(opfBytes)
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "item" -> {
                            val id = attr(p, "id")
                            val href = attr(p, "href")
                            if (id != null && href != null) manifest[id] = href
                        }
                        "itemref" -> attr(p, "idref")?.let { spine.add(it) }
                    }
                }
                e = p.next()
            }
        }

        val cw = (A4_W - 2 * MARGIN).toFloat()
        val chh = (A4_H - 2 * MARGIN).toFloat()
        val paint = basePaint(12.5f)
        val head = Regex("(?is)<head.*?</head>")
        val style = Regex("(?is)<style.*?</style>")
        val script = Regex("(?is)<script.*?</script>")
        val svg = Regex("(?is)<svg.*?</svg>")
        val prolog = Regex("(?is)<\\?xml.*?\\?>")
        val doctype = Regex("(?is)<!DOCTYPE.*?>")
        val href = Regex("(?i)href=\"([^\"]+)\"")

        for (idref in spine) {
            if (pdf.full()) break
            val rel = manifest[idref] ?: continue
            val path = resolve(opf, rel)
            val raw = z.bytes(path) ?: continue
            var html = String(raw, Charsets.UTF_8)
            html = html.replace(prolog, "").replace(doctype, "").replace(head, "")
                .replace(style, "").replace(script, "")
            html = html.replace(svg) { m ->
                val h = href.find(m.value)?.groupValues?.get(1)
                if (h != null) "<img src=\"$h\"/>" else ""
            }
            val getter = Html.ImageGetter { src ->
                val b = z.bytes(resolve(path, src))
                val d = if (b != null) imageDrawable(ctx, b, 0f, 0f, cw, chh * 0.85f) else null
                d ?: blank(1)
            }
            val sp: Spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, getter, null)
            var end = sp.length
            while (end > 0 && (sp[end - 1] == '\n' || sp[end - 1] == ' ')) end--
            if (end == 0) continue
            paginate(pdf, sp.subSequence(0, end), paint)
        }
    }

    // =====================================================================
    // DOCX
    // =====================================================================

    private fun docx(ctx: Context, z: Zip, pdf: PdfOut) {
        val body = z.bytes("word/document.xml") ?: throw IllegalStateException("document.xml yok")
        val rels = readRels(z, "word/_rels/document.xml.rels")
        val cw = (A4_W - 2 * MARGIN).toFloat()
        val chh = (A4_H - 2 * MARGIN).toFloat()

        val sb = SpannableStringBuilder()
        val p = parser(body)

        var pStart = 0
        var pScale = 1f
        var pBold = false
        var pAlign: Layout.Alignment? = null
        var pBullet = false

        var rStart = 0
        var rBold = false
        var rItalic = false
        var rUnder = false
        var rSize = 0f
        var rColor = 0

        var extW = 0f
        var extH = 0f
        var tblDepth = 0

        fun flag(v: String?): Boolean = v == null || (v != "0" && v != "false" && v != "off")

        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                when (p.name) {
                    "w:p" -> {
                        pStart = sb.length
                        pScale = 1f
                        pBold = false
                        pAlign = null
                        pBullet = false
                    }
                    "w:pStyle" -> {
                        val v = (attr(p, "w:val") ?: "").lowercase()
                        if (v == "title") {
                            pScale = 1.9f
                            pBold = true
                        } else if (v.startsWith("heading") || v.startsWith("balk") || v.startsWith("başlık")) {
                            val lvl = v.filter { it.isDigit() }.toIntOrNull() ?: 1
                            val sc = floatArrayOf(1.7f, 1.45f, 1.25f, 1.1f, 1.05f, 1f)
                            pScale = sc[(lvl - 1).coerceIn(0, 5)]
                            pBold = true
                        }
                    }
                    "w:jc" -> {
                        when (attr(p, "w:val")) {
                            "center" -> pAlign = Layout.Alignment.ALIGN_CENTER
                            "right", "end" -> pAlign = Layout.Alignment.ALIGN_OPPOSITE
                        }
                    }
                    "w:numPr" -> pBullet = true
                    "w:r" -> {
                        rStart = sb.length
                        rBold = false
                        rItalic = false
                        rUnder = false
                        rSize = 0f
                        rColor = 0
                    }
                    "w:b" -> rBold = flag(attr(p, "w:val"))
                    "w:i" -> rItalic = flag(attr(p, "w:val"))
                    "w:u" -> rUnder = (attr(p, "w:val") ?: "single") != "none"
                    "w:sz" -> rSize = (attr(p, "w:val")?.toFloatOrNull() ?: 0f) / 2f
                    "w:color" -> {
                        val c = attr(p, "w:val")
                        if (c != null && c.length == 6 && c != "auto") {
                            rColor = try { 0xFF000000.toInt() or c.toInt(16) } catch (ex: Exception) { 0 }
                        }
                    }
                    "w:t" -> sb.append(p.nextText())
                    "w:tab" -> sb.append('\t')
                    "w:br" -> sb.append('\n')
                    "w:tbl" -> tblDepth++
                    "wp:extent" -> {
                        extW = (attr(p, "cx")?.toFloatOrNull() ?: 0f) / 12700f
                        extH = (attr(p, "cy")?.toFloatOrNull() ?: 0f) / 12700f
                    }
                    "a:blip" -> {
                        val id = attr(p, "r:embed")
                        val target = if (id != null) rels[id] else null
                        if (target != null) {
                            val b = z.bytes(resolve("word/document.xml", target))
                            val d = if (b != null) imageDrawable(ctx, b, extW, extH, cw, chh * 0.85f) else null
                            if (d != null) {
                                val at = sb.length
                                sb.append('￼')
                                sb.setSpan(ImageSpan(d, ImageSpan.ALIGN_BASELINE), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                    }
                }
            } else if (e == XmlPullParser.END_TAG) {
                when (p.name) {
                    "w:r" -> {
                        val s = rStart
                        val t = sb.length
                        if (t > s) {
                            if (rBold || pBold) sb.setSpan(StyleSpan(if (rItalic) Typeface.BOLD_ITALIC else Typeface.BOLD), s, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            else if (rItalic) sb.setSpan(StyleSpan(Typeface.ITALIC), s, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            if (rUnder) sb.setSpan(UnderlineSpan(), s, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            if (rSize > 0f) sb.setSpan(AbsoluteSizeSpan(rSize.toInt().coerceAtLeast(6), false), s, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            if (rColor != 0) sb.setSpan(ForegroundColorSpan(rColor), s, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    "w:p" -> {
                        if (pBullet) sb.insert(pStart, "• ")
                        val s = pStart
                        sb.append('\n')
                        val t = sb.length
                        if (pScale != 1f) sb.setSpan(RelativeSizeSpan(pScale), s, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        val al = pAlign
                        if (al != null) sb.setSpan(AlignmentSpan.Standard(al), s, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    "w:tc" -> {
                        if (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.replace(sb.length - 1, sb.length, "  │  ")
                    }
                    "w:tr" -> {
                        val tail = "  │  "
                        val l = sb.length
                        if (l >= tail.length && sb.subSequence(l - tail.length, l).toString() == tail) {
                            sb.replace(l - tail.length, l, "\n")
                        } else {
                            sb.append('\n')
                        }
                    }
                    "w:tbl" -> if (tblDepth > 0) tblDepth--
                }
            }
            e = p.next()
        }
        var end = sb.length
        while (end > 0 && sb[end - 1] == '\n') end--
        if (end == 0) throw IllegalStateException("boş belge")
        paginate(pdf, sb.subSequence(0, end), basePaint(11f))
    }

    // =====================================================================
    // XLSX
    // =====================================================================

    private class SheetData(val name: String) {
        val rows = ArrayList<HashMap<Int, String>>()
        var maxCol = 0
    }

    private fun colIndex(ref: String): Int {
        var c = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') c = c * 26 + (ch - 'A' + 1) else break
        }
        return c - 1
    }

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, ('A' + r))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun fmtNum(v: String): String {
        val d = v.toDoubleOrNull() ?: return v
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return java.text.DecimalFormat("0.######").format(d)
    }

    private fun xlsx(z: Zip, pdf: PdfOut) {
        val wb = z.bytes("xl/workbook.xml") ?: throw IllegalStateException("workbook yok")
        val rels = readRels(z, "xl/_rels/workbook.xml.rels")

        val shared = ArrayList<String>()
        val ssBytes = z.bytes("xl/sharedStrings.xml")
        if (ssBytes != null) {
            val p = parser(ssBytes)
            val cur = StringBuilder()
            var rph = 0
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "si" -> cur.setLength(0)
                        "rPh" -> rph++
                        "t" -> {
                            val t = p.nextText()
                            if (rph == 0) cur.append(t)
                        }
                    }
                } else if (e == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "si" -> shared.add(cur.toString())
                        "rPh" -> rph--
                    }
                }
                e = p.next()
            }
        }

        val sheetRefs = ArrayList<Pair<String, String>>()
        run {
            val p = parser(wb)
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && p.name == "sheet") {
                    val name = attr(p, "name") ?: "Sayfa"
                    val rid = attr(p, "r:id")
                    val target = if (rid != null) rels[rid] else null
                    if (target != null) sheetRefs.add(Pair(name, resolve("xl/workbook.xml", target)))
                }
                e = p.next()
            }
        }

        val paint = basePaint(9f)
        val titlePaint = basePaint(10f)
        titlePaint.color = 0xFF666666.toInt()
        titlePaint.typeface = Typeface.DEFAULT_BOLD
        val grid = Paint()
        grid.color = 0xFFCCCCCC.toInt()
        grid.strokeWidth = 0.6f

        for ((name, path) in sheetRefs) {
            if (pdf.full()) break
            val sb = z.bytes(path) ?: continue
            val sheet = SheetData(name)
            val p = parser(sb)
            var row: HashMap<Int, String>? = null
            var ref = ""
            var type = ""
            var v = ""
            var inl = ""
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT && sheet.rows.size < 3000) {
                if (e == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "row" -> row = HashMap()
                        "c" -> {
                            ref = attr(p, "r") ?: ""
                            type = attr(p, "t") ?: ""
                            v = ""
                            inl = ""
                        }
                        "v" -> v = p.nextText()
                        "t" -> inl += p.nextText()
                    }
                } else if (e == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "c" -> {
                            val col = colIndex(ref)
                            val text = when (type) {
                                "s" -> v.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                "b" -> if (v == "1") "DOĞRU" else "YANLIŞ"
                                "str", "e" -> v
                                "inlineStr" -> inl
                                else -> if (v.isEmpty()) inl else fmtNum(v)
                            }
                            if (col >= 0 && text.isNotEmpty()) {
                                row?.put(col, text)
                                if (col + 1 > sheet.maxCol) sheet.maxCol = col + 1
                            }
                        }
                        "row" -> {
                            val r = row
                            if (r != null && r.isNotEmpty()) sheet.rows.add(r)
                            row = null
                        }
                    }
                }
                e = p.next()
            }
            if (sheet.rows.isEmpty()) continue
            renderSheet(pdf, sheet, paint, titlePaint, grid)
        }
    }

    private fun renderSheet(pdf: PdfOut, sheet: SheetData, paint: TextPaint, titlePaint: TextPaint, grid: Paint) {
        val pw = 842
        val ph = 595
        val m = 28f
        val rowH = 16f
        val titleH = 20f
        // sütun genişlikleri
        val widths = FloatArray(sheet.maxCol) { 28f }
        for (r in sheet.rows.take(300)) {
            for ((c, t) in r) {
                if (c < widths.size) {
                    val w = paint.measureText(t) + 8f
                    if (w > widths[c]) widths[c] = w
                }
            }
        }
        for (i in widths.indices) widths[i] = widths[i].coerceAtMost(200f)

        // sütun grupları
        val groups = ArrayList<IntArray>()
        var start = 0
        var acc = 0f
        val avail = pw - 2 * m
        for (i in widths.indices) {
            if (acc + widths[i] > avail && i > start) {
                groups.add(intArrayOf(start, i))
                start = i
                acc = 0f
            }
            acc += widths[i]
        }
        groups.add(intArrayOf(start, widths.size))

        val rowsPerPage = ((ph - 2 * m - titleH) / rowH).toInt().coerceAtLeast(1)
        var r0 = 0
        while (r0 < sheet.rows.size && !pdf.full()) {
            val r1 = minOf(r0 + rowsPerPage, sheet.rows.size)
            for (g in groups) {
                if (pdf.full()) break
                val label = sheet.name + "  (" + colName(g[0]) + "–" + colName(g[1] - 1) + ", satır " + (r0 + 1) + "–" + r1 + ")"
                pdf.page(pw, ph) { c ->
                    c.drawText(label, m, m - 6f, titlePaint)
                    var x = m
                    val xs = FloatArray(g[1] - g[0] + 1)
                    for (ci in g[0] until g[1]) {
                        xs[ci - g[0]] = x
                        x += widths[ci]
                    }
                    xs[g[1] - g[0]] = x
                    val top = m + titleH - 6f
                    val bottom = top + (r1 - r0) * rowH
                    for (k in xs.indices) c.drawLine(xs[k], top, xs[k], bottom, grid)
                    for (ri in 0..(r1 - r0)) {
                        val y = top + ri * rowH
                        c.drawLine(xs[0], y, x, y, grid)
                    }
                    for (ri in r0 until r1) {
                        val row = sheet.rows[ri]
                        val y = top + (ri - r0) * rowH
                        for (ci in g[0] until g[1]) {
                            val t = row[ci] ?: continue
                            val cx = xs[ci - g[0]]
                            c.save()
                            c.clipRect(RectF(cx + 1f, y, cx + widths[ci] - 1f, y + rowH))
                            c.drawText(t, cx + 3f, y + rowH - 4.5f, paint)
                            c.restore()
                        }
                    }
                }
            }
            r0 = r1
        }
    }

    // =====================================================================
    // PPTX
    // =====================================================================

    private class Run(val text: String, val size: Float, val bold: Boolean, val italic: Boolean)
    private class Para(val runs: ArrayList<Run>, val bullet: Boolean, val align: Layout.Alignment?)
    private class Shape(
        var x: Float, var y: Float, var w: Float, var h: Float,
        val ph: String?, val paras: ArrayList<Para>, val embed: String?,
    )

    private fun pptx(z: Zip, pdf: PdfOut) {
        val pres = z.bytes("ppt/presentation.xml") ?: throw IllegalStateException("presentation yok")
        val rels = readRels(z, "ppt/_rels/presentation.xml.rels")
        var sw = 960f
        var sh = 540f
        val slides = ArrayList<String>()
        run {
            val p = parser(pres)
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "p:sldSz" -> {
                            val cx = attr(p, "cx")?.toFloatOrNull()
                            val cy = attr(p, "cy")?.toFloatOrNull()
                            if (cx != null && cy != null && cx > 0 && cy > 0) {
                                sw = (cx / 12700f).coerceIn(300f, 2000f)
                                sh = (cy / 12700f).coerceIn(200f, 2000f)
                            }
                        }
                        "p:sldId" -> {
                            val t = attr(p, "r:id")?.let { rels[it] }
                            if (t != null) slides.add(resolve("ppt/presentation.xml", t))
                        }
                    }
                }
                e = p.next()
            }
        }
        for (path in slides) {
            if (pdf.full()) break
            val b = z.bytes(path) ?: continue
            val relPath = path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels"
            val srels = readRels(z, relPath)
            val shapes = parseSlide(b)
            pdf.page(sw.toInt(), sh.toInt()) { c -> drawSlide(c, z, path, srels, shapes, sw, sh) }
        }
    }

    private fun parseSlide(b: ByteArray): List<Shape> {
        val out = ArrayList<Shape>()
        val p = parser(b)
        var inShape = false
        var kind = ""
        var x = -1f
        var y = -1f
        var w = -1f
        var h = -1f
        var ph: String? = null
        var embed: String? = null
        var paras = ArrayList<Para>()
        var runs = ArrayList<Run>()
        var bullet = false
        var align: Layout.Alignment? = null
        var sz = 0f
        var bold = false
        var italic = false
        var inTbl = false
        val cell = StringBuilder()
        val rowText = StringBuilder()

        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                val n = p.name
                if (!inShape && (n == "p:sp" || n == "p:pic" || n == "p:graphicFrame")) {
                    inShape = true
                    kind = n
                    x = -1f; y = -1f; w = -1f; h = -1f
                    ph = null
                    embed = null
                    paras = ArrayList()
                    runs = ArrayList()
                } else if (inShape) {
                    when (n) {
                        "p:ph" -> ph = attr(p, "type") ?: "body"
                        "a:off" -> {
                            x = (attr(p, "x")?.toFloatOrNull() ?: 0f) / 12700f
                            y = (attr(p, "y")?.toFloatOrNull() ?: 0f) / 12700f
                        }
                        "a:ext" -> {
                            val cx = attr(p, "cx")?.toFloatOrNull()
                            val cy = attr(p, "cy")?.toFloatOrNull()
                            if (cx != null && cy != null) {
                                w = cx / 12700f
                                h = cy / 12700f
                            }
                        }
                        "a:blip" -> embed = attr(p, "r:embed")
                        "a:tbl" -> inTbl = true
                        "a:tc" -> cell.setLength(0)
                        "a:p" -> {
                            runs = ArrayList()
                            bullet = false
                            align = null
                        }
                        "a:pPr" -> when (attr(p, "algn")) {
                            "ctr" -> align = Layout.Alignment.ALIGN_CENTER
                            "r" -> align = Layout.Alignment.ALIGN_OPPOSITE
                        }
                        "a:buChar" -> bullet = true
                        "a:r" -> {
                            sz = 0f
                            bold = false
                            italic = false
                        }
                        "a:rPr" -> {
                            sz = (attr(p, "sz")?.toFloatOrNull() ?: 0f) / 100f
                            bold = attr(p, "b") == "1"
                            italic = attr(p, "i") == "1"
                        }
                        "a:t" -> {
                            val t = p.nextText()
                            if (inTbl) cell.append(t) else runs.add(Run(t, sz, bold, italic))
                        }
                        "a:br" -> if (!inTbl) runs.add(Run("\n", sz, bold, italic))
                    }
                }
            } else if (e == XmlPullParser.END_TAG && inShape) {
                when (p.name) {
                    "a:p" -> if (!inTbl) paras.add(Para(runs, bullet, align))
                    "a:tc" -> rowText.append(cell.toString().replace('\n', ' ')).append("  │  ")
                    "a:tr" -> {
                        val line = rowText.toString().removeSuffix("  │  ")
                        paras.add(Para(arrayListOf(Run(line, 12f, false, false)), false, null))
                        rowText.setLength(0)
                    }
                    "a:tbl" -> inTbl = false
                    kind -> {
                        inShape = false
                        val hasText = paras.any { pa -> pa.runs.any { r -> r.text.isNotBlank() } }
                        if (kind == "p:pic") {
                            if (embed != null) out.add(Shape(x, y, w, h, null, ArrayList(), embed))
                        } else if (hasText) {
                            out.add(Shape(x, y, w, h, ph, paras, null))
                        }
                    }
                }
            }
            e = p.next()
        }
        return out
    }

    private fun buildSlideText(sh: Shape, scale: Float): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val def = if (sh.ph == "title" || sh.ph == "ctrTitle") 32f else 18f
        for (pa in sh.paras) {
            val s = sb.length
            if (pa.bullet) sb.append("• ")
            for (r in pa.runs) {
                val a = sb.length
                sb.append(r.text)
                val t = sb.length
                if (t > a) {
                    val size = ((if (r.size > 0f) r.size else def) * scale).toInt().coerceAtLeast(5)
                    sb.setSpan(AbsoluteSizeSpan(size, false), a, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (r.bold || sh.ph == "title" || sh.ph == "ctrTitle") {
                        sb.setSpan(StyleSpan(if (r.italic) Typeface.BOLD_ITALIC else Typeface.BOLD), a, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else if (r.italic) {
                        sb.setSpan(StyleSpan(Typeface.ITALIC), a, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            sb.append('\n')
            val al = pa.align
            if (al != null) sb.setSpan(AlignmentSpan.Standard(al), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun drawSlide(c: Canvas, z: Zip, slidePath: String, rels: Map<String, String>, shapes: List<Shape>, sw: Float, sh: Float) {
        val paint = basePaint(18f)
        for (s in shapes) {
            var x = s.x
            var y = s.y
            var w = s.w
            var h = s.h
            if (x < 0f || w <= 0f) {
                val isTitle = s.ph == "title" || s.ph == "ctrTitle"
                x = sw * 0.06f
                w = sw * 0.88f
                y = if (isTitle) sh * 0.04f else sh * 0.25f
                h = if (isTitle) sh * 0.2f else sh * 0.65f
            }
            if (s.embed != null) {
                val t = rels[s.embed] ?: continue
                val b = z.bytes(resolve(slidePath, t)) ?: continue
                val bmp = decodeImage(b, (w * 2).toInt().coerceAtLeast(64)) ?: continue
                c.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), null)
                continue
            }
            var scale = 1f
            var layout = makeLayout(buildSlideText(s, scale), paint, w)
            if (layout.height > h && h > 0f) {
                scale = Math.sqrt((h / layout.height).toDouble()).toFloat().coerceIn(0.45f, 1f)
                layout = makeLayout(buildSlideText(s, scale), paint, w)
            }
            c.save()
            c.translate(x, y)
            layout.draw(c)
            c.restore()
        }
    }

    private fun makeLayout(text: CharSequence, paint: TextPaint, w: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, w.toInt().coerceAtLeast(40))
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()
}
