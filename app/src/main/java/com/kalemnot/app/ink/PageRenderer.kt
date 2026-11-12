package com.kalemnot.app.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.kalemnot.app.data.InkSeg
import com.kalemnot.app.data.Page
import com.kalemnot.app.data.Stroke
import com.kalemnot.app.data.Template
import com.kalemnot.app.data.TextItem

/**
 * Bir sayfayı android.graphics.Canvas üzerine çizer. Ekran, küçük resim ve PDF çıktısı aynı kodu kullanır.
 * Paint nesneleri paylaşıldığı için her iş parçacığı kendi örneğini kullanmalıdır.
 */
class PageRenderer {
    private val shadowPaint = Paint().apply { color = 0x26000000 }
    private val paperPaint = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFAEB6C6.toInt() }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    fun drawPage(c: Canvas, page: Page, bg: Bitmap?, shadow: Boolean = true, useCache: Boolean = true) {
        val w = page.width
        val h = page.height
        if (shadow) c.drawRect(3f, 4f, w + 3f, h + 4f, shadowPaint)
        c.drawRect(0f, 0f, w, h, paperPaint)
        if (bg != null) {
            if (page.pdfIndex < 0) {
                // görsel: oranı koru, sayfanın üstüne yerleştir; altı yazı için boş kalır
                dst.set(0f, 0f, w, w * bg.height / bg.width)
            } else {
                dst.set(0f, 0f, w, h)
            }
            c.drawBitmap(bg, null, dst, bmpPaint)
        } else if (page.src == null) {
            drawTemplate(c, page)
        }
        c.save()
        c.clipRect(0f, 0f, w, h)
        for (s in page.strokes) drawStroke(c, s, useCache)
        for (t in page.texts) drawText(c, t)
        c.restore()
    }

    private fun drawTemplate(c: Canvas, page: Page) {
        val w = page.width
        val h = page.height
        when (page.template) {
            Template.BLANK -> Unit
            Template.LINED -> {
                linePaint.color = 0xFFC5CFE6.toInt()
                var y = 110f
                while (y < h - 20f) {
                    c.drawLine(40f, y, w - 40f, y, linePaint)
                    y += 34f
                }
                linePaint.color = 0xFFE9A3A3.toInt()
                c.drawLine(90f, 0f, 90f, h, linePaint)
            }
            Template.GRID -> {
                linePaint.color = 0xFFD5DAE3.toInt()
                var x = 32f
                while (x < w) {
                    c.drawLine(x, 0f, x, h, linePaint)
                    x += 32f
                }
                var y = 32f
                while (y < h) {
                    c.drawLine(0f, y, w, y, linePaint)
                    y += 32f
                }
            }
            Template.DOT -> {
                var y = 32f
                while (y < h) {
                    var x = 32f
                    while (x < w) {
                        c.drawCircle(x, y, 1.6f, dotPaint)
                        x += 32f
                    }
                    y += 32f
                }
            }
        }
    }

    private fun drawStroke(c: Canvas, s: Stroke, useCache: Boolean) {
        val segs: ArrayList<InkSeg> = if (useCache) {
            var cached = s.cache
            if (cached == null || s.cacheVersion != s.version) {
                cached = buildSegs(s)
                s.cache = cached
                s.cacheVersion = s.version
            }
            cached
        } else {
            buildSegs(s)
        }
        inkPaint.color = s.color
        if (s.highlighter) {
            inkPaint.alpha = 100
            inkPaint.strokeCap = Paint.Cap.SQUARE
        } else {
            inkPaint.alpha = 255
            inkPaint.strokeCap = Paint.Cap.ROUND
        }
        for (seg in segs) {
            inkPaint.strokeWidth = seg.width
            c.drawPath(seg.path, inkPaint)
        }
    }

    private fun drawText(c: Canvas, t: TextItem) {
        textPaint.color = t.color
        textPaint.textSize = t.size
        var y = t.y + t.size
        for (line in t.text.split('\n')) {
            c.drawText(line, t.x, y, textPaint)
            y += t.size * 1.25f
        }
    }

    /** Metin kutusunun sayfa içindeki sınırları (dokunma testi için). */
    fun textBounds(t: TextItem, out: RectF) {
        textPaint.textSize = t.size
        val lines = t.text.split('\n')
        var maxW = 0f
        for (l in lines) {
            val m = textPaint.measureText(l)
            if (m > maxW) maxW = m
        }
        out.set(t.x, t.y, t.x + maxW, t.y + lines.size * t.size * 1.25f)
    }

    companion object {
        private fun widthFor(s: Stroke, p: Float): Float {
            if (s.highlighter) return s.width
            val w = s.width * (0.5f + 0.8f * p.coerceIn(0f, 1f))
            return Math.round(w * 4f) / 4f
        }

        fun buildSegs(s: Stroke): ArrayList<InkSeg> {
            val out = ArrayList<InkSeg>()
            val n = s.pointCount
            if (n == 0) return out
            val d = s.data
            if (n == 1) {
                val p = Path()
                p.moveTo(d[0], d[1])
                p.lineTo(d[0] + 0.1f, d[1])
                out.add(InkSeg(p, widthFor(s, d[2])))
                return out
            }
            var path = Path()
            var curW = widthFor(s, d[2])
            path.moveTo(d[0], d[1])
            var px = d[0]
            var py = d[1]
            for (i in 1 until n) {
                val x = d[3 * i]
                val y = d[3 * i + 1]
                val p = d[3 * i + 2]
                val mx = (px + x) / 2f
                val my = (py + y) / 2f
                path.quadTo(px, py, mx, my)
                val w = widthFor(s, p)
                if (w != curW) {
                    out.add(InkSeg(path, curW))
                    path = Path()
                    path.moveTo(mx, my)
                    curW = w
                }
                px = x
                py = y
            }
            path.lineTo(px, py)
            out.add(InkSeg(path, curW))
            return out
        }

        /** Silgi için: (x, y) noktası çizgiye r yarıçapında yakın mı? */
        fun hits(s: Stroke, x: Float, y: Float, r: Float): Boolean {
            val pad = r + s.width * 0.5f
            if (x < s.minX - pad || x > s.maxX + pad || y < s.minY - pad || y > s.maxY + pad) return false
            val n = s.pointCount
            val d = s.data
            val lim = pad * pad
            if (n == 1) {
                val dx = x - d[0]
                val dy = y - d[1]
                return dx * dx + dy * dy <= lim
            }
            for (i in 0 until n - 1) {
                val ax = d[3 * i]
                val ay = d[3 * i + 1]
                val bx = d[3 * i + 3]
                val by = d[3 * i + 4]
                val abx = bx - ax
                val aby = by - ay
                val len2 = abx * abx + aby * aby
                var t = if (len2 == 0f) 0f else ((x - ax) * abx + (y - ay) * aby) / len2
                if (t < 0f) t = 0f else if (t > 1f) t = 1f
                val cx = ax + t * abx - x
                val cy = ay + t * aby - y
                if (cx * cx + cy * cy <= lim) return true
            }
            return false
        }
    }
}
