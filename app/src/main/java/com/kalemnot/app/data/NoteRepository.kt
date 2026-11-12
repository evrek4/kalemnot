package com.kalemnot.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.kalemnot.app.ink.PageRenderer
import kotlinx.coroutines.asCoroutineDispatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Bir kaydetme işi: ana iş parçacığında hazırlanır, arka planda diske yazılır. */
class SaveJob(private val writes: List<Pair<File, String>>, private val deletes: List<File>) {
    fun run() {
        for ((file, text) in writes) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            } catch (e: Exception) {
                // Bir sonraki kaydetmede tekrar denenir.
            }
        }
        for (f in deletes) {
            try { f.delete() } catch (e: Exception) { }
        }
    }
}

class NoteRepository(private val context: Context) {
    val root: File = File(context.filesDir, "notes").also { it.mkdirs() }

    /** Tüm disk yazma işleri sırayla çalışsın diye tek iş parçacıklı. */
    val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    fun dir(id: String): File = File(root, id)

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(14)

    // ---------- Liste ----------

    fun listSummaries(): List<NoteSummary> {
        val out = ArrayList<NoteSummary>()
        root.listFiles()?.forEach { d ->
            val f = File(d, "note.json")
            if (d.isDirectory && f.exists()) {
                try {
                    val o = JSONObject(f.readText())
                    out.add(
                        NoteSummary(
                            id = o.getString("id"),
                            title = o.optString("title", "Başlıksız"),
                            colorIdx = o.optInt("color", 0),
                            createdAt = o.optLong("created"),
                            updatedAt = o.optLong("updated"),
                            pageCount = o.optJSONArray("pages")?.length() ?: 0,
                            recCount = o.optJSONArray("recs")?.length() ?: 0,
                        )
                    )
                } catch (e: Exception) {
                    // bozuk not atlanır
                }
            }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    // ---------- Yükleme ----------

    fun load(id: String): Note? {
        val f = File(dir(id), "note.json")
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val note = Note(
                id,
                o.optString("title", "Başlıksız"),
                o.optInt("color", 0),
                o.optLong("created"),
                o.optLong("updated"),
            )
            val pa = o.optJSONArray("pages") ?: JSONArray()
            for (i in 0 until pa.length()) {
                val p = pa.getJSONObject(i)
                val src = if (p.has("src")) p.getString("src") else null
                val page = Page(
                    p.getString("id"),
                    Template.entries.getOrElse(p.optInt("t", 0)) { Template.BLANK },
                    src,
                    p.optInt("pi", -1),
                    p.optDouble("w", PAGE_W.toDouble()).toFloat(),
                    p.optDouble("h", PAGE_H.toDouble()).toFloat(),
                )
                loadInk(id, page)
                note.pages.add(page)
            }
            val ra = o.optJSONArray("recs") ?: JSONArray()
            for (i in 0 until ra.length()) {
                val r = ra.getJSONObject(i)
                note.recordings.add(Recording(r.getString("f"), r.optString("t", "Kayıt"), r.optLong("c"), r.optLong("d")))
            }
            if (note.pages.isEmpty()) {
                note.pages.add(Page(newId(), Template.BLANK, null, -1, PAGE_W, PAGE_H))
            }
            note
        } catch (e: Exception) {
            null
        }
    }

    private fun loadInk(noteId: String, page: Page) {
        val f = File(dir(noteId), "p_${page.id}.json")
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            val sa = o.optJSONArray("s")
            if (sa != null) {
                for (i in 0 until sa.length()) {
                    val so = sa.getJSONObject(i)
                    val st = Stroke(so.optInt("h") == 1, so.getInt("c"), so.getDouble("w").toFloat())
                    val d = so.getJSONArray("d")
                    var k = 0
                    while (k + 2 < d.length()) {
                        st.add(d.getDouble(k).toFloat(), d.getDouble(k + 1).toFloat(), d.getDouble(k + 2).toFloat())
                        k += 3
                    }
                    if (st.pointCount > 0) page.strokes.add(st)
                }
            }
            val ta = o.optJSONArray("x")
            if (ta != null) {
                for (i in 0 until ta.length()) {
                    val t = ta.getJSONObject(i)
                    page.texts.add(
                        TextItem(
                            t.optLong("id", System.nanoTime() + i),
                            t.getDouble("x").toFloat(),
                            t.getDouble("y").toFloat(),
                            t.getString("s"),
                            t.getInt("c"),
                            t.getDouble("z").toFloat(),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // bozuk sayfa boş açılır
        }
    }

    // ---------- Kaydetme ----------

    private fun fmt(v: Float): Float = Math.round(v * 10f) / 10f
    private fun fmtP(v: Float): Float = Math.round(v * 100f) / 100f

    private fun pageJson(page: Page): String {
        val sb = StringBuilder()
        sb.append("{\"s\":[")
        page.strokes.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append("{\"h\":").append(if (s.highlighter) 1 else 0)
            sb.append(",\"c\":").append(s.color)
            sb.append(",\"w\":").append(s.width)
            sb.append(",\"d\":[")
            var k = 0
            while (k < s.n) {
                if (k > 0) sb.append(',')
                sb.append(fmt(s.data[k])).append(',').append(fmt(s.data[k + 1])).append(',').append(fmtP(s.data[k + 2]))
                k += 3
            }
            sb.append("]}")
        }
        sb.append("],\"x\":[")
        page.texts.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(t.id)
            sb.append(",\"x\":").append(fmt(t.x))
            sb.append(",\"y\":").append(fmt(t.y))
            sb.append(",\"s\":").append(JSONObject.quote(t.text))
            sb.append(",\"c\":").append(t.color)
            sb.append(",\"z\":").append(t.size)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun noteJson(note: Note): String {
        val o = JSONObject()
        o.put("id", note.id)
        o.put("title", note.title)
        o.put("color", note.colorIdx)
        o.put("created", note.createdAt)
        o.put("updated", note.updatedAt)
        val pa = JSONArray()
        for (p in note.pages) {
            val po = JSONObject()
            po.put("id", p.id)
            po.put("t", p.template.ordinal)
            if (p.src != null) po.put("src", p.src)
            po.put("pi", p.pdfIndex)
            po.put("w", p.width.toDouble())
            po.put("h", p.height.toDouble())
            pa.put(po)
        }
        o.put("pages", pa)
        val ra = JSONArray()
        for (r in note.recordings) {
            val ro = JSONObject()
            ro.put("f", r.file)
            ro.put("t", r.title)
            ro.put("c", r.createdAt)
            ro.put("d", r.durationMs)
            ra.put(ro)
        }
        o.put("recs", ra)
        return o.toString()
    }

    /** Ana iş parçacığında çağrılır; değişen sayfaları ve not bilgisini yazılacak hale getirir. */
    fun snapshot(note: Note): SaveJob {
        val d = dir(note.id)
        val writes = ArrayList<Pair<File, String>>()
        for (p in note.pages) {
            if (p.dirty) {
                writes.add(Pair(File(d, "p_${p.id}.json"), pageJson(p)))
                p.dirty = false
            }
        }
        writes.add(Pair(File(d, "note.json"), noteJson(note)))
        val deletes = ArrayList(note.trash)
        note.trash.clear()
        return SaveJob(writes, deletes)
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    /** Notu tamamen yüklemeden başlık / renk günceller. */
    fun updateMeta(id: String, title: String?, color: Int?) {
        val f = File(dir(id), "note.json")
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            if (title != null) o.put("title", title)
            if (color != null) o.put("color", color)
            o.put("updated", System.currentTimeMillis())
            SaveJob(listOf(Pair(f, o.toString())), emptyList()).run()
        } catch (e: Exception) {
            // yok sayılır
        }
    }

    // ---------- Oluşturma / içe aktarma ----------

    fun createEmpty(title: String, template: Template): Note {
        val now = System.currentTimeMillis()
        val note = Note(newId(), title, (now % 8).toInt(), now, now)
        val page = Page(newId(), template, null, -1, PAGE_W, PAGE_H)
        page.dirty = true
        note.pages.add(page)
        dir(note.id).mkdirs()
        snapshot(note).run()
        return note
    }

    private fun displayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** PDF veya görselleri not klasörüne kopyalar ve sayfalarını döndürür. Arka planda çağrılmalıdır. */
    fun importPages(noteId: String, uris: List<Uri>): List<Page> {
        val pages = ArrayList<Page>()
        val d = dir(noteId)
        d.mkdirs()
        for (u in uris) {
            val mime = context.contentResolver.getType(u) ?: ""
            val name = displayName(u) ?: (u.lastPathSegment ?: "")
            val lower = name.lowercase()
            val isPdf = mime == "application/pdf" || lower.endsWith(".pdf")
            val isImg = mime.startsWith("image/") ||
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".bmp")
            val docKind = DocConverter.kindOf(mime, lower)
            if (!isPdf && !isImg && docKind == null) continue
            val asPdf = isPdf || docKind != null
            val ext = if (asPdf) "pdf" else {
                val e = lower.substringAfterLast('.', "jpg").filter { it.isLetterOrDigit() }.take(5)
                if (e.isEmpty()) "jpg" else e
            }
            val fileName = "src_${newId()}.$ext"
            val dest = File(d, fileName)
            if (docKind != null) {
                val tmp = File(context.cacheDir, "conv_${newId()}")
                val ok = try {
                    val input = context.contentResolver.openInputStream(u)
                    if (input == null) false else {
                        input.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                        DocConverter.convert(context, tmp, docKind, dest)
                    }
                } catch (e: Throwable) {
                    false
                } finally {
                    tmp.delete()
                }
                if (!ok) {
                    dest.delete()
                    continue
                }
            } else {
                try {
                    val input = context.contentResolver.openInputStream(u) ?: continue
                    input.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
                } catch (e: Exception) {
                    dest.delete()
                    continue
                }
            }
            if (asPdf) {
                try {
                    val fd = ParcelFileDescriptor.open(dest, ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = PdfRenderer(fd)
                    try {
                        for (i in 0 until r.pageCount) {
                            val p = r.openPage(i)
                            val w = p.width
                            val h = p.height
                            p.close()
                            val ph = if (w > 0) PAGE_W * h / w else PAGE_H
                            pages.add(Page(newId(), Template.BLANK, fileName, i, PAGE_W, ph))
                        }
                    } finally {
                        r.close()
                        fd.close()
                    }
                } catch (e: Exception) {
                    dest.delete()
                }
            } else {
                val size = ImageUtil.size(dest)
                if (size == null) {
                    dest.delete()
                } else {
                    val ph = (PAGE_W * size.second / size.first).coerceIn(PAGE_H, 6000f)
                    pages.add(Page(newId(), Template.BLANK, fileName, -1, PAGE_W, ph))
                }
            }
        }
        return pages
    }

    /** Dosya(lar)dan yeni not oluşturur; desteklenen dosya yoksa null döner. */
    fun createFromUris(uris: List<Uri>): Note? {
        val now = System.currentTimeMillis()
        val id = newId()
        val title = uris.firstNotNullOfOrNull { displayName(it) }
            ?.substringBeforeLast('.')
            ?.ifBlank { null }
            ?: "İçe aktarılan not"
        val note = Note(id, title, (now % 8).toInt(), now, now)
        val pages = importPages(id, uris)
        if (pages.isEmpty()) {
            dir(id).deleteRecursively()
            return null
        }
        note.pages.addAll(pages)
        snapshot(note).run()
        return note
    }

    // ---------- Küçük resim ----------

    fun thumbFile(id: String) = File(dir(id), "thumb.png")

    fun loadThumb(id: String): Bitmap? {
        val f = thumbFile(id)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    /** İlk sayfanın küçük resmini üretir. Arka planda çağrılmalıdır. */
    fun writeThumb(noteId: String, first: Page) {
        try {
            val tw = 420
            val scale = tw / first.width
            val th = (first.height * scale).toInt().coerceAtMost((tw * 1.35f).toInt()).coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            val bg = SourceLoader(dir(noteId)).use { it.load(first, 520) }
            PageRenderer().drawPage(c, first, bg, shadow = false, useCache = false)
            thumbFile(noteId).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bmp.recycle()
        } catch (e: Throwable) {
            // küçük resim zorunlu değil
        }
    }

    fun ensureThumb(id: String) {
        if (thumbFile(id).exists()) return
        val n = load(id) ?: return
        n.pages.firstOrNull()?.let { writeThumb(id, it) }
    }
}
