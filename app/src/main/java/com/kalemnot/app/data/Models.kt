package com.kalemnot.app.data

import android.graphics.Path
import java.io.File

enum class ToolType { PEN, HIGHLIGHTER, ERASER, TEXT }

enum class Template { BLANK, LINED, GRID, DOT }

const val PAGE_W = 794f
const val PAGE_H = 1123f

/** Çizilmiş bir parça segmenti (aynı kalınlıkta art arda noktalar). */
class InkSeg(val path: Path, val width: Float)

/** Bir kalem veya fosforlu kalem çizgisi. Noktalar x, y, basınç üçlüsü olarak düz dizide tutulur. */
class Stroke(val highlighter: Boolean, val color: Int, val width: Float) {
    var data = FloatArray(192)
    var n = 0
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    var version = 0
    var cache: ArrayList<InkSeg>? = null
    var cacheVersion = -1

    val pointCount: Int get() = n / 3

    fun add(x: Float, y: Float, p: Float) {
        if (n + 3 > data.size) data = data.copyOf(data.size * 2)
        data[n] = x
        data[n + 1] = y
        data[n + 2] = p
        n += 3
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
        version++
    }
}

class TextItem(
    val id: Long,
    var x: Float,
    var y: Float,
    var text: String,
    var color: Int,
    var size: Float,
)

class Page(
    val id: String,
    var template: Template,
    /** Not klasöründeki kaynak dosya adı (PDF veya görsel); yoksa null. */
    var src: String?,
    /** PDF ise sayfa numarası, görsel ise -1. */
    var pdfIndex: Int,
    var width: Float,
    var height: Float,
) {
    val strokes = ArrayList<Stroke>()
    val texts = ArrayList<TextItem>()
    var dirty = false

    /** Dışa aktarma ve küçük resim için yüzeysel kopya. */
    fun snapshot(): Page {
        val p = Page(id, template, src, pdfIndex, width, height)
        p.strokes.addAll(strokes)
        p.texts.addAll(texts.map { TextItem(it.id, it.x, it.y, it.text, it.color, it.size) })
        return p
    }
}

class Recording(val file: String, var title: String, val createdAt: Long, val durationMs: Long)

class Note(
    val id: String,
    var title: String,
    var colorIdx: Int,
    val createdAt: Long,
    var updatedAt: Long,
) {
    val pages = ArrayList<Page>()
    val recordings = ArrayList<Recording>()

    /** Kayıttan sonra silinecek dosyalar (silinen sayfaların mürekkep dosyaları vb.). */
    val trash = ArrayList<File>()
}

class NoteSummary(
    val id: String,
    val title: String,
    val colorIdx: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    val recCount: Int,
)
