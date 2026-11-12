package com.kalemnot.app.ink

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.kalemnot.app.data.AudioRecorder
import com.kalemnot.app.data.Note
import com.kalemnot.app.data.NoteRepository
import com.kalemnot.app.data.PAGE_H
import com.kalemnot.app.data.PAGE_W
import com.kalemnot.app.data.Page
import com.kalemnot.app.data.PdfExporter
import com.kalemnot.app.data.Prefs
import com.kalemnot.app.data.Recording
import com.kalemnot.app.data.Stroke
import com.kalemnot.app.data.Template
import com.kalemnot.app.data.TextItem
import com.kalemnot.app.data.ToolType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

interface UiBridge {
    fun busy(msg: String?)
    fun message(msg: String)
}

class EraseRec(val page: Page, val index: Int, val stroke: Stroke)

class TextSnap(val x: Float, val y: Float, val text: String, val color: Int, val size: Float)

sealed class Op
class AddStrokeOp(val page: Page, val stroke: Stroke) : Op()
class EraseOp(val recs: List<EraseRec>) : Op()
class AddTextOp(val page: Page, val item: TextItem) : Op()
class DeleteTextOp(val page: Page, val index: Int, val item: TextItem) : Op()
class ChangeTextOp(val page: Page, val item: TextItem, val old: TextSnap, val new: TextSnap) : Op()

class TextDlg(val page: Page, val item: TextItem?, val x: Float, val y: Float)

class TextHit(val page: Page, val item: TextItem)

class EditorState(
    val note: Note,
    private val repo: NoteRepository,
    val prefs: Prefs,
    private val scope: CoroutineScope,
    private val ui: UiBridge,
) {
    companion object {
        const val GAP = 24f
        const val MARGIN = 24f
        const val SIDE = 20f
        const val BOTTOM_PAD = 130f
        val PEN_WIDTHS = floatArrayOf(1.6f, 2.6f, 4.5f)
        val HL_WIDTHS = floatArrayOf(14f, 22f, 34f)
        val TEXT_SIZES = floatArrayOf(18f, 26f, 38f)
        const val ERASE_R = 14f
    }

    // ---- Görünüm durumu ----
    var redraw by mutableIntStateOf(0)
    var scale by mutableFloatStateOf(1f)
    var offX by mutableFloatStateOf(0f)
    var offY by mutableFloatStateOf(0f)
    var currentPage by mutableIntStateOf(0)
    var pageCount by mutableIntStateOf(note.pages.size)
    var viewW = 0f
    var viewH = 0f
    var animScope: CoroutineScope? = null

    // ---- Araç durumu ----
    var tool by mutableStateOf(ToolType.PEN)
    var penColor by mutableIntStateOf(prefs.penColor)
    var hlColor by mutableIntStateOf(prefs.hlColor)
    var sizeIdx by mutableIntStateOf(prefs.sizeIdx)
    var fingerDraws by mutableStateOf(prefs.fingerDraws)
    var title by mutableStateOf(note.title)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var textDialog by mutableStateOf<TextDlg?>(null)

    // ---- Ses kaydı ----
    var recording by mutableStateOf(false)
    var recMillis by mutableLongStateOf(0L)
    var recVersion by mutableIntStateOf(0)

    val renderer = PageRenderer()
    val bitmaps = PageBitmaps(repo, note.id, scope) { redraw++ }

    private var tops = FloatArray(0)
    private var totalH = 0f
    private var initialized = false

    private val undoStack = ArrayList<Op>()
    private val redoStack = ArrayList<Op>()
    private var saveJob: Job? = null
    private var flingJob: Job? = null

    private var cur: Stroke? = null
    private var curPage: Page? = null
    private var curTop = 0f
    private var erased: ArrayList<EraseRec>? = null
    private var dragHit: TextHit? = null
    private var dragStart: TextSnap? = null
    private val tmpRect = android.graphics.RectF()

    private var recorder: AudioRecorder? = null
    private var recFile: File? = null
    private var recStart = 0L
    private var recTicker: Job? = null

    init {
        relayout()
    }

    // =====================================================================
    // Yerleşim ve kaydırma / yakınlaştırma
    // =====================================================================

    fun topOf(i: Int): Float = if (i in tops.indices) tops[i] else 0f

    fun relayout() {
        tops = FloatArray(note.pages.size)
        var y = MARGIN
        for (i in note.pages.indices) {
            tops[i] = y
            y += note.pages[i].height + GAP
        }
        totalH = y + BOTTOM_PAD
        pageCount = note.pages.size
    }

    fun fitScale(): Float = if (viewW <= 0f) 1f else viewW / (PAGE_W + 2 * SIDE)

    fun onViewSize(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val ratio = if (initialized) scale / fitScale() else 1f
        viewW = w
        viewH = h
        if (!initialized) {
            initialized = true
            scale = fitScale()
            offX = (w - PAGE_W * scale) / 2f
            offY = 0f
        } else {
            scale = fitScale() * ratio
        }
        clamp()
        updateCurrentPage()
        redraw++
    }

    private fun clamp() {
        val cw = PAGE_W * scale
        val ch = totalH * scale
        offX = if (cw + 2 * SIDE * scale <= viewW) (viewW - cw) / 2f
        else offX.coerceIn(viewW - cw - SIDE * scale, SIDE * scale)
        offY = if (ch <= viewH) 0f else offY.coerceIn(viewH - ch, 0f)
    }

    private fun updateCurrentPage() {
        val cy = (viewH / 2f - offY) / scale
        var idx = 0
        for (i in tops.indices) {
            if (cy >= tops[i] - GAP / 2f) idx = i
        }
        if (idx != currentPage) currentPage = idx
    }

    fun transformBy(centroid: Offset, pan: Offset, zoom: Float) {
        val fit = fitScale()
        val newScale = (scale * zoom).coerceIn(fit * 0.5f, fit * 10f)
        val k = newScale / scale
        offX = centroid.x - (centroid.x - pan.x - offX) * k
        offY = centroid.y - (centroid.y - pan.y - offY) * k
        scale = newScale
        clamp()
        updateCurrentPage()
    }

    fun panBy(d: Offset) {
        offX += d.x
        offY += d.y
        clamp()
        updateCurrentPage()
    }

    fun stopFling() {
        flingJob?.cancel()
        flingJob = null
    }

    fun fling(vx: Float, vy: Float) {
        val s = animScope ?: return
        stopFling()
        flingJob = s.launch {
            val ax = Animatable(0f)
            val ay = Animatable(0f)
            var lx = 0f
            var ly = 0f
            launch {
                ax.animateDecay(vx, exponentialDecay()) {
                    panBy(Offset(value - lx, 0f))
                    lx = value
                }
            }
            launch {
                ay.animateDecay(vy, exponentialDecay()) {
                    panBy(Offset(0f, value - ly))
                    ly = value
                }
            }
        }
    }

    fun goToPage(i: Int) {
        if (i !in note.pages.indices) return
        stopFling()
        offY = -tops[i] * scale + 16f
        clamp()
        updateCurrentPage()
        redraw++
    }

    fun toDoc(p: Offset): Offset = Offset((p.x - offX) / scale, (p.y - offY) / scale)

    private fun pageAt(x: Float, y: Float): Int {
        val tol = GAP / 2f
        if (x < -tol || x > PAGE_W + tol) return -1
        for (i in note.pages.indices) {
            if (y >= tops[i] - tol && y <= tops[i] + note.pages[i].height + tol) return i
        }
        return -1
    }

    // =====================================================================
    // Araçlar
    // =====================================================================

    val activeColor: Int get() = if (tool == ToolType.HIGHLIGHTER) hlColor else penColor

    fun setColor(c: Int) {
        if (tool == ToolType.HIGHLIGHTER) {
            hlColor = c
            prefs.hlColor = c
        } else {
            penColor = c
            prefs.penColor = c
        }
    }

    fun setSize(i: Int) {
        sizeIdx = i
        prefs.sizeIdx = i
    }

    fun toggleFinger() {
        fingerDraws = !fingerDraws
        prefs.fingerDraws = fingerDraws
        prefs.stylusAutoSet = true
    }

    fun onStylusDetected() {
        if (prefs.stylusAutoSet) return
        prefs.stylusAutoSet = true
        if (fingerDraws) {
            fingerDraws = false
            prefs.fingerDraws = false
            ui.message("Kalem algılandı: parmakla kaydırır, kalemle yazarsın. Parmakla çizmek için alttaki el simgesine dokun.")
        }
    }

    // =====================================================================
    // Çizgi çizme
    // =====================================================================

    fun beginStroke(pos: Offset, pressure: Float): Boolean {
        stopFling()
        val d = toDoc(pos)
        val i = pageAt(d.x, d.y)
        if (i < 0) return false
        val page = note.pages[i]
        val hl = tool == ToolType.HIGHLIGHTER
        val s = Stroke(
            hl,
            if (hl) hlColor else penColor,
            if (hl) HL_WIDTHS[sizeIdx] else PEN_WIDTHS[sizeIdx],
        )
        s.add(d.x, d.y - tops[i], pressure)
        page.strokes.add(s)
        cur = s
        curPage = page
        curTop = tops[i]
        redraw++
        return true
    }

    fun addPoint(pos: Offset, pressure: Float) {
        val s = cur ?: return
        val d = toDoc(pos)
        val lx = d.x
        val ly = d.y - curTop
        val n = s.n
        if (n >= 3) {
            val dx = lx - s.data[n - 3]
            val dy = ly - s.data[n - 2]
            if (dx * dx + dy * dy < 0.25f) return
        }
        s.add(lx, ly, pressure)
        redraw++
    }

    fun endStroke() {
        val s = cur ?: return
        val p = curPage ?: return
        cur = null
        curPage = null
        pushOp(AddStrokeOp(p, s))
        touch(p)
        redraw++
    }

    fun cancelStroke() {
        val s = cur
        val p = curPage
        if (s != null && p != null) p.strokes.remove(s)
        cur = null
        curPage = null
        redraw++
    }

    // =====================================================================
    // Silgi
    // =====================================================================

    fun eraseAt(pos: Offset) {
        val d = toDoc(pos)
        val i = pageAt(d.x, d.y)
        if (i < 0) return
        val page = note.pages[i]
        val lx = d.x
        val ly = d.y - tops[i]
        val list = erased ?: ArrayList<EraseRec>().also { erased = it }
        var changed = false
        var k = page.strokes.size - 1
        while (k >= 0) {
            val s = page.strokes[k]
            if (PageRenderer.hits(s, lx, ly, ERASE_R)) {
                page.strokes.removeAt(k)
                list.add(EraseRec(page, k, s))
                changed = true
            }
            k--
        }
        if (changed) redraw++
    }

    fun endErase() {
        val list = erased ?: return
        erased = null
        if (list.isEmpty()) return
        pushOp(EraseOp(list))
        for (p in list.map { it.page }.distinct()) touch(p)
    }

    // =====================================================================
    // Metin
    // =====================================================================

    private fun snap(t: TextItem) = TextSnap(t.x, t.y, t.text, t.color, t.size)

    private fun restore(t: TextItem, s: TextSnap) {
        t.x = s.x
        t.y = s.y
        t.text = s.text
        t.color = s.color
        t.size = s.size
    }

    fun hitText(pos: Offset): TextHit? {
        val d = toDoc(pos)
        val i = pageAt(d.x, d.y)
        if (i < 0) return null
        val page = note.pages[i]
        val lx = d.x
        val ly = d.y - tops[i]
        for (k in page.texts.indices.reversed()) {
            val t = page.texts[k]
            renderer.textBounds(t, tmpRect)
            tmpRect.inset(-10f, -10f)
            if (tmpRect.contains(lx, ly)) return TextHit(page, t)
        }
        return null
    }

    fun beginTextDrag(h: TextHit) {
        dragHit = h
        dragStart = snap(h.item)
    }

    fun dragText(delta: Offset) {
        val h = dragHit ?: return
        h.item.x += delta.x / scale
        h.item.y += delta.y / scale
        redraw++
    }

    fun endTextDrag() {
        val h = dragHit ?: return
        val old = dragStart
        dragHit = null
        dragStart = null
        if (old != null) {
            pushOp(ChangeTextOp(h.page, h.item, old, snap(h.item)))
            touch(h.page)
        }
    }

    fun endTextDragCancel() {
        dragHit = null
        dragStart = null
    }

    fun requestNewText(pos: Offset) {
        val d = toDoc(pos)
        val i = pageAt(d.x, d.y)
        if (i < 0) return
        textDialog = TextDlg(note.pages[i], null, d.x, d.y - tops[i])
    }

    fun requestEditText(h: TextHit) {
        textDialog = TextDlg(h.page, h.item, h.item.x, h.item.y)
    }

    fun commitText(dlg: TextDlg, text: String) {
        textDialog = null
        val item = dlg.item
        if (item == null) {
            if (text.isBlank()) return
            val t = TextItem(System.nanoTime(), dlg.x, dlg.y, text, penColor, TEXT_SIZES[sizeIdx])
            dlg.page.texts.add(t)
            pushOp(AddTextOp(dlg.page, t))
        } else {
            if (text.isBlank()) {
                deleteText(dlg)
                return
            }
            val old = snap(item)
            item.text = text
            pushOp(ChangeTextOp(dlg.page, item, old, snap(item)))
        }
        touch(dlg.page)
        redraw++
    }

    fun deleteText(dlg: TextDlg) {
        textDialog = null
        val item = dlg.item ?: return
        val idx = dlg.page.texts.indexOf(item)
        if (idx < 0) return
        dlg.page.texts.removeAt(idx)
        pushOp(DeleteTextOp(dlg.page, idx, item))
        touch(dlg.page)
        redraw++
    }

    // =====================================================================
    // Geri al / yinele
    // =====================================================================

    private fun pushOp(op: Op) {
        undoStack.add(op)
        if (undoStack.size > 200) undoStack.removeAt(0)
        redoStack.clear()
        syncFlags()
    }

    private fun syncFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val op = undoStack.removeAt(undoStack.size - 1)
        when (op) {
            is AddStrokeOp -> {
                op.page.strokes.remove(op.stroke)
                touch(op.page)
            }
            is EraseOp -> {
                for (r in op.recs.reversed()) {
                    r.page.strokes.add(r.index.coerceAtMost(r.page.strokes.size), r.stroke)
                }
                for (p in op.recs.map { it.page }.distinct()) touch(p)
            }
            is AddTextOp -> {
                op.page.texts.remove(op.item)
                touch(op.page)
            }
            is DeleteTextOp -> {
                op.page.texts.add(op.index.coerceAtMost(op.page.texts.size), op.item)
                touch(op.page)
            }
            is ChangeTextOp -> {
                restore(op.item, op.old)
                touch(op.page)
            }
        }
        redoStack.add(op)
        syncFlags()
        redraw++
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val op = redoStack.removeAt(redoStack.size - 1)
        when (op) {
            is AddStrokeOp -> {
                op.page.strokes.add(op.stroke)
                touch(op.page)
            }
            is EraseOp -> {
                for (r in op.recs) r.page.strokes.remove(r.stroke)
                for (p in op.recs.map { it.page }.distinct()) touch(p)
            }
            is AddTextOp -> {
                op.page.texts.add(op.item)
                touch(op.page)
            }
            is DeleteTextOp -> {
                op.page.texts.remove(op.item)
                touch(op.page)
            }
            is ChangeTextOp -> {
                restore(op.item, op.new)
                touch(op.page)
            }
        }
        undoStack.add(op)
        syncFlags()
        redraw++
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        syncFlags()
    }

    // =====================================================================
    // Sayfa işlemleri
    // =====================================================================

    private fun structureChanged() {
        clearHistory()
        relayout()
        clamp()
        updateCurrentPage()
        touchMeta()
        redraw++
    }

    fun addPage(template: Template) {
        val at = (currentPage + 1).coerceIn(0, note.pages.size)
        val p = Page(repo.newId(), template, null, -1, PAGE_W, PAGE_H)
        p.dirty = true
        note.pages.add(at, p)
        structureChanged()
        goToPage(at)
    }

    fun deleteCurrentPage() {
        val i = currentPage
        if (i !in note.pages.indices) return
        if (note.pages.size <= 1) {
            val p = note.pages[0]
            p.strokes.clear()
            p.texts.clear()
            touch(p)
            clearHistory()
            redraw++
            return
        }
        val p = note.pages.removeAt(i)
        note.trash.add(File(repo.dir(note.id), "p_${p.id}.json"))
        structureChanged()
        goToPage((i - 1).coerceAtLeast(0))
    }

    fun setTemplate(t: Template) {
        val p = note.pages.getOrNull(currentPage) ?: return
        if (p.src != null) return
        p.template = t
        touch(p)
        redraw++
    }

    fun currentPageHasSource(): Boolean = note.pages.getOrNull(currentPage)?.src != null

    fun importUris(uris: List<Uri>): Job {
        return scope.launch {
            ui.busy("İçe aktarılıyor…")
            val pages = withContext(repo.io) { repo.importPages(note.id, uris) }
            ui.busy(null)
            if (pages.isEmpty()) {
                ui.message("Desteklenen dosya bulunamadı (PDF, görsel, Word, Excel, PowerPoint veya EPUB olmalı)")
                return@launch
            }
            val at = (currentPage + 1).coerceIn(0, note.pages.size)
            pages.forEachIndexed { k, p ->
                p.dirty = true
                note.pages.add(at + k, p)
            }
            structureChanged()
            goToPage(at)
            ui.message("${pages.size} sayfa eklendi")
        }
    }

    fun rename(t: String) {
        val s = t.trim()
        if (s.isEmpty()) return
        note.title = s
        title = s
        touchMeta()
    }

    // =====================================================================
    // Kaydetme
    // =====================================================================

    private fun touch(p: Page) {
        p.dirty = true
        touchMeta()
    }

    private fun touchMeta() {
        note.updatedAt = System.currentTimeMillis()
        prefs.lastChange = note.updatedAt
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1200)
            val job = repo.snapshot(note)
            withContext(repo.io) { job.run() }
        }
    }

    /** Bekleyen değişiklikleri hemen diske yazdırır. */
    fun flush() {
        saveJob?.cancel()
        val job = repo.snapshot(note)
        scope.launch(repo.io) { job.run() }
    }

    /** Editörü kapatır: kaydeder, küçük resmi günceller. Biten işi döndürür. */
    fun close(): Job {
        if (recording) stopRecording()
        stopFling()
        saveJob?.cancel()
        val job = repo.snapshot(note)
        val first = note.pages.firstOrNull()?.snapshot()
        val id = note.id
        bitmaps.close()
        return scope.launch(repo.io) {
            job.run()
            if (first != null) repo.writeThumb(id, first)
        }
    }

    // =====================================================================
    // PDF dışa aktarma
    // =====================================================================

    fun exportPdf(ctx: Context) {
        scope.launch {
            ui.busy("PDF hazırlanıyor…")
            val snaps = note.pages.map { it.snapshot() }
            val dir = repo.dir(note.id)
            val t = note.title
            try {
                val file = withContext(Dispatchers.IO) { PdfExporter.export(ctx.applicationContext, dir, t, snaps) }
                ui.busy(null)
                PdfExporter.share(ctx, file)
            } catch (e: Throwable) {
                ui.busy(null)
                ui.message("PDF oluşturulamadı")
            }
        }
    }

    // =====================================================================
    // Ses kaydı
    // =====================================================================

    fun recordingFile(r: Recording): File = File(repo.dir(note.id), r.file)

    fun startRecording(ctx: Context) {
        if (recording) return
        val f = File(repo.dir(note.id), "rec_${repo.newId()}.m4a")
        f.parentFile?.mkdirs()
        val r = AudioRecorder(ctx.applicationContext)
        if (!r.start(f)) {
            ui.message("Kayıt başlatılamadı")
            return
        }
        recorder = r
        recFile = f
        recording = true
        recStart = SystemClock.elapsedRealtime()
        recMillis = 0L
        recTicker = scope.launch {
            while (true) {
                recMillis = SystemClock.elapsedRealtime() - recStart
                delay(500)
            }
        }
    }

    fun stopRecording() {
        val r = recorder ?: return
        val f = recFile
        recTicker?.cancel()
        recTicker = null
        val ms = SystemClock.elapsedRealtime() - recStart
        r.stop()
        recorder = null
        recFile = null
        recording = false
        if (f != null && f.exists() && ms > 800) {
            val n = note.recordings.size + 1
            note.recordings.add(Recording(f.name, "Kayıt $n", System.currentTimeMillis(), ms))
            recVersion++
            touchMeta()
            ui.message("Ses kaydı eklendi")
        } else {
            f?.delete()
        }
    }

    fun deleteRecording(r: Recording) {
        recordingFile(r).delete()
        note.recordings.remove(r)
        recVersion++
        touchMeta()
    }
}
