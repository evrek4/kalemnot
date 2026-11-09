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

        if (!wantsDraw) {
            transformLoop(s, down)
            return@awaitEachGesture
        }
        when (tool) {
            ToolType.PEN, ToolType.HIGHLIGHTER -> drawLoop(s, down, isPen)
            ToolType.ERASER -> eraseLoop(s, down, isPen)
            ToolType.TEXT -> textLoop(s, down, isPen)
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

private suspend fun AwaitPointerEventScope.drawLoop(s: EditorState, down: PointerInputChange, isPen: Boolean) {
    var lastP = pressureOf(down, isPen)
    if (!s.beginStroke(down.position, lastP)) return
    down.consume()
    while (true) {
        val event = awaitPointerEvent()
        if (!isPen && event.changes.count { it.pressed } > 1) {
            // İkinci parmak: çizimi iptal et, kaydırma / yakınlaştırmaya geç.
            s.cancelStroke()
            transformLoop(s, event.changes.first { it.pressed })
            return
        }
        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
        // Avuç içi: kalem çizerken diğer dokunuşları yut.
        event.changes.forEach { if (it.id != down.id) it.consume() }
        if (ch.pressed) {
            for (h in ch.historical) s.addPoint(h.position, lastP)
            lastP = lastP * 0.6f + pressureOf(ch, isPen) * 0.4f
            s.addPoint(ch.position, lastP)
            ch.consume()
        } else {
            ch.consume()
            break
        }
    }
    s.endStroke()
}

private suspend fun AwaitPointerEventScope.eraseLoop(s: EditorState, down: PointerInputChange, isPen: Boolean) {
    s.eraseAt(down.position)
    down.consume()
    while (true) {
        val event = awaitPointerEvent()
        if (!isPen && event.changes.count { it.pressed } > 1) {
            s.endErase()
            transformLoop(s, event.changes.first { it.pressed })
            return
        }
        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
        event.changes.forEach { if (it.id != down.id) it.consume() }
        if (ch.pressed) {
            for (h in ch.historical) s.eraseAt(h.position)
            s.eraseAt(ch.position)
            ch.consume()
        } else {
            ch.consume()
            break
        }
    }
    s.endErase()
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
