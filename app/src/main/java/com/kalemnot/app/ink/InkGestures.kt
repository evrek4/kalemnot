@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.kalemnot.app.ink

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import com.kalemnot.app.data.ToolType

/**
 * Parmak / kalem ayrımı:
 *  - Kalem (veya kalemin silgi ucu) ve fare her zaman çizer.
 *  - Parmak, "parmakla çiz" açıksa çizer; kapalıysa kaydırır ve yakınlaştırır (avuç içi reddi).
 *  - İki parmak her zaman kaydırma / yakınlaştırma yapar.
 */
suspend fun PointerInputScope.inkGestures(s: EditorState) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        s.stopFling()
        val type = down.type
        val isPen = type == PointerType.Stylus || type == PointerType.Eraser
        if (isPen) s.onStylusDetected()
        val tool = if (type == PointerType.Eraser) ToolType.ERASER else s.tool
        val wantsDraw = isPen || type == PointerType.Mouse || (type == PointerType.Touch && s.fingerDraws)
        // Parmakla çizerken çizgi parmağın biraz yukarısında çıkar; böylece çizilen yer parmağın altında kalmaz.
        val off = if (type == PointerType.Touch) 44.dp.toPx() else 0f

        if (type == PointerType.Touch) {
            when (touchPrelude(s, down)) {
                1 -> {
                    handleTouchTap(s, down, wantsDraw, tool, off)
                    return@awaitEachGesture
                }
                2 -> return@awaitEachGesture
            }
        }

        if (!wantsDraw) {
            transformLoop(s, down)
            return@awaitEachGesture
        }
        when (tool) {
            ToolType.PEN, ToolType.HIGHLIGHTER -> drawLoop(s, down, isPen, off)
            ToolType.ERASER -> eraseLoop(s, down, isPen, off)
            ToolType.TEXT -> textLoop(s, down, isPen)
        }
    }
}

/**
 * Parmak değdikten sonra kısa süre bekler: 1 = kısa dokunuş (bırakıldı), 2 = parmak kayboldu,
 * 0 = dokunuş devam ediyor / hareket etti (normal işleme geç).
 */
private suspend fun AwaitPointerEventScope.touchPrelude(s: EditorState, down: PointerInputChange): Int {
    val slop = viewConfiguration.touchSlop
    val r: Int? = withTimeoutOrNull(220L) {
        var out = 0
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) {
                out = 0
                break
            }
            val ch = event.changes.firstOrNull { it.id == down.id }
            if (ch == null) {
                out = 2
                break
            }
            if (!ch.pressed) {
                ch.consume()
                out = 1
                break
            }
            if ((ch.position - down.position).getDistance() > slop) {
                out = 0
                break
            }
        }
        out
    }
    return r ?: 0
}

/** Kısa parmak dokunuşu: çift dokunuş kalem modunu açar/kapatır; tek dokunuş araca göre nokta/silme/metin yapar. */
private fun handleTouchTap(s: EditorState, down: PointerInputChange, wantsDraw: Boolean, tool: ToolType, off: Float) {
    val now = down.uptimeMillis
    if (tool != ToolType.TEXT) {
        if (s.lastTapUp > 0L && now - s.lastTapUp < 350L && (down.position - s.lastTapPos).getDistance() < 120f) {
            s.lastTapUp = 0L
            s.dropTapDot()
            s.toggleFingerByTap()
            return
        }
        s.lastTapUp = now
        s.lastTapPos = down.position
    } else {
        s.lastTapUp = 0L
    }
    if (!wantsDraw) return
    val pos = Offset(down.position.x, down.position.y - off)
    when (tool) {
        ToolType.PEN, ToolType.HIGHLIGHTER -> {
            if (s.beginStroke(pos, 0.6f)) s.endStroke()
        }
        ToolType.ERASER -> {
            s.eraseAt(pos)
            s.endErase()
        }
        ToolType.TEXT -> {
            val hit = s.hitText(down.position)
            if (hit != null) s.requestEditText(hit) else s.requestNewText(down.position)
        }
    }
}

private fun pressureOf(c: PointerInputChange, isPen: Boolean): Float =
    if (isPen) c.pressure.coerceIn(0.05f, 1f) else 0.6f

private suspend fun AwaitPointerEventScope.transformLoop(s: EditorState, first: PointerInputChange) {
    val tracker = VelocityTracker()
    var multi = false
    tracker.addPosition(first.uptimeMillis, first.position)
    while (true) {
        val event = awaitPointerEvent()
        val pressedCount = event.changes.count { it.pressed }
        if (pressedCount == 0) break
        if (pressedCount > 1) multi = true
        val zoom = event.calculateZoom()
        val pan = event.calculatePan()
        val centroid = event.calculateCentroid()
        if (centroid != Offset.Unspecified) {
            tracker.addPosition(event.changes[0].uptimeMillis, centroid)
            if (zoom != 1f || pan != Offset.Zero) {
                s.transformBy(centroid, pan, zoom)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    }
    if (!multi) {
        val v = tracker.calculateVelocity()
        if (kotlin.math.abs(v.x) > 200f || kotlin.math.abs(v.y) > 200f) s.fling(v.x, v.y)
    }
}

private fun PointerInputChange.positionChanged(): Boolean = positionChange() != Offset.Zero

private suspend fun AwaitPointerEventScope.drawLoop(s: EditorState, down: PointerInputChange, isPen: Boolean, off: Float) {
    fun fp(p: Offset) = Offset(p.x, p.y - off)
    var lastP = pressureOf(down, isPen)
    if (!s.beginStroke(fp(down.position), lastP)) return
    down.consume()
    try {
        if (off > 0f) s.penCursor = fp(down.position)
        while (true) {
            val event = awaitPointerEvent()
            if (!isPen && event.changes.count { it.pressed } > 1) {
                // İkinci parmak: çizimi iptal et, kaydırma / yakınlaştırmaya geç.
                s.cancelStroke()
                s.penCursor = null
                transformLoop(s, event.changes.first { it.pressed })
                return
            }
            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
            // Avuç içi: kalem çizerken diğer dokunuşları yut.
            event.changes.forEach { if (it.id != down.id) it.consume() }
            if (ch.pressed) {
                for (h in ch.historical) s.addPoint(fp(h.position), lastP)
                lastP = lastP * 0.6f + pressureOf(ch, isPen) * 0.4f
                s.addPoint(fp(ch.position), lastP)
                if (off > 0f) s.penCursor = fp(ch.position)
                ch.consume()
            } else {
                ch.consume()
                break
            }
        }
        s.endStroke()
    } finally {
        s.penCursor = null
    }
}

private suspend fun AwaitPointerEventScope.eraseLoop(s: EditorState, down: PointerInputChange, isPen: Boolean, off: Float) {
    fun fp(p: Offset) = Offset(p.x, p.y - off)
    s.eraseAt(fp(down.position))
    down.consume()
    try {
        if (off > 0f) s.penCursor = fp(down.position)
        while (true) {
            val event = awaitPointerEvent()
            if (!isPen && event.changes.count { it.pressed } > 1) {
                s.endErase()
                s.penCursor = null
                transformLoop(s, event.changes.first { it.pressed })
                return
            }
            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
            event.changes.forEach { if (it.id != down.id) it.consume() }
            if (ch.pressed) {
                for (h in ch.historical) s.eraseAt(fp(h.position))
                s.eraseAt(fp(ch.position))
                if (off > 0f) s.penCursor = fp(ch.position)
                ch.consume()
            } else {
                ch.consume()
                break
            }
        }
        s.endErase()
    } finally {
        s.penCursor = null
    }
}

private suspend fun AwaitPointerEventScope.textLoop(s: EditorState, down: PointerInputChange, isPen: Boolean) {
    val hit = s.hitText(down.position)
    if (hit != null) s.beginTextDrag(hit)
    var moved = false
    down.consume()
    while (true) {
        val event = awaitPointerEvent()
        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
        event.changes.forEach { if (it.id != down.id) it.consume() }
        if (!ch.pressed) {
            ch.consume()
            if (!moved) {
                if (hit != null) {
                    s.endTextDragCancel()
                    s.requestEditText(hit)
                } else {
                    s.requestNewText(down.position)
                }
            } else if (hit != null) {
                s.endTextDrag()
            }
            return
        }
        val dist = (ch.position - down.position).getDistance()
        if (!moved && dist > viewConfiguration.touchSlop) moved = true
        if (moved) {
            val d = ch.positionChange()
            if (hit != null) s.dragText(d) else s.panBy(d)
        }
        ch.consume()
    }
    if (hit != null) {
        if (moved) s.endTextDrag() else s.endTextDragCancel()
    }
}
