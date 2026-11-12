package com.kalemnot.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kalemnot.app.AppViewModel
import com.kalemnot.app.data.AudioPlayer
import com.kalemnot.app.data.Template
import com.kalemnot.app.data.ToolType
import com.kalemnot.app.ink.EditorState
import com.kalemnot.app.ink.inkGestures

private enum class EDlg { NONE, RENAME, ADD_PAGE, GOTO, DELETE_PAGE, RECORDINGS, TEMPLATE, FINGER }

private fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return String.format("%d:%02d", s / 60, s % 60)
}

private val penColors = listOf(
    0xFF1B1B1FL, 0xFF1565C0L, 0xFFD32F2FL, 0xFF2E7D32L, 0xFFEF6C00L, 0xFF6A1B9AL, 0xFF795548L,
).map { it.toInt() }

private val hlColors = listOf(
    0xFFFFEB3BL, 0xFF69F0AEL, 0xFFFF80ABL, 0xFF80D8FFL, 0xFFFFAB40L,
).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel, st: EditorState, onPickImport: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf(EDlg.NONE) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) st.startRecording(ctx) else vm.message("Ses kaydı için mikrofon izni gerekli")
    }

    fun toggleRecord() {
        if (st.recording) {
            st.stopRecording()
        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            st.startRecording(ctx)
        } else {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        st.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { dlg = EDlg.RENAME },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { vm.closeEditor() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri")
                    }
                },
                actions = {
                    IconButton(onClick = { st.undo() }, enabled = st.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Geri al")
                    }
                    IconButton(onClick = { st.redo() }, enabled = st.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "Yinele")
                    }
                    IconButton(onClick = { toggleRecord() }) {
                        if (st.recording) {
                            Icon(Icons.Filled.Stop, "Kaydı durdur", tint = Color(0xFFD32F2F))
                        } else {
                            Icon(Icons.Filled.Mic, "Ses kaydı")
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menü") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Sayfa ekle…") },
                                onClick = { menu = false; dlg = EDlg.ADD_PAGE },
                            )
                            DropdownMenuItem(
                                text = { Text("PDF / görsel ekle…") },
                                onClick = { menu = false; onPickImport() },
                            )
                            DropdownMenuItem(
                                text = { Text("Sayfaya git…") },
                                onClick = { menu = false; dlg = EDlg.GOTO },
                            )
                            if (!st.currentPageHasSource()) {
                                DropdownMenuItem(
                                    text = { Text("Sayfa şablonunu değiştir…") },
                                    onClick = { menu = false; dlg = EDlg.TEMPLATE },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Bu sayfayı sil") },
                                onClick = { menu = false; dlg = EDlg.DELETE_PAGE },
                            )
                            DropdownMenuItem(
                                text = { Text("Ses kayıtları (" + st.note.recordings.size + ")") },
                                onClick = { menu = false; dlg = EDlg.RECORDINGS },
                            )
                            DropdownMenuItem(
                                text = { Text("PDF olarak paylaş") },
                                onClick = { menu = false; st.exportPdf(ctx) },
                            )
                            DropdownMenuItem(
                                text = { Text(if (st.fingerDraws) "Parmakla çizim: açık" else "Parmakla çizim: kapalı") },
                                onClick = { menu = false; st.toggleFinger() },
                            )
                            DropdownMenuItem(
                                text = { Text("Parmakla çizim konumu…") },
                                onClick = { menu = false; dlg = EDlg.FINGER },
                            )
                            DropdownMenuItem(
                                text = { Text("Yeniden adlandır") },
                                onClick = { menu = false; dlg = EDlg.RENAME },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            InkCanvas(st, Modifier.fillMaxSize())

            if (st.recording) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFD32F2F),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                        Spacer(Modifier.width(8.dp))
                        Text(fmtDur(st.recMillis), color = Color.White)
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { dlg = EDlg.GOTO },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 3.dp,
            ) {
                Text(
                    (st.currentPage + 1).toString() + " / " + st.pageCount,
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            InkToolbar(st, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }

    // ---- Diyaloglar ----

    when (dlg) {
        EDlg.NONE -> Unit
        EDlg.RENAME -> {
            var text by remember { mutableStateOf(st.title) }
            AlertDialog(
                onDismissRequest = { dlg = EDlg.NONE },
                title = { Text("Not adı") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = { TextButton(onClick = { st.rename(text); dlg = EDlg.NONE }) { Text("Kaydet") } },
                dismissButton = { TextButton(onClick = { dlg = EDlg.NONE }) { Text("İptal") } },
            )
        }
        EDlg.ADD_PAGE -> TemplateDialog("Sayfa ekle", { dlg = EDlg.NONE }) { t -> st.addPage(t); dlg = EDlg.NONE }
        EDlg.TEMPLATE -> TemplateDialog("Sayfa şablonu", { dlg = EDlg.NONE }) { t -> st.setTemplate(t); dlg = EDlg.NONE }
        EDlg.FINGER -> {
            AlertDialog(
                onDismissRequest = { dlg = EDlg.NONE },
                title = { Text("Parmakla çizim konumu") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Çizgi parmağına göre nerede çıksın?", style = MaterialTheme.typography.bodyMedium)
                        val dirs = listOf("Parmağın altında (kayma yok)", "Yukarıda", "Yukarı sol", "Yukarı sağ")
                        for (i in dirs.indices) {
                            Row(
                                Modifier.fillMaxWidth().clickable { st.setOffsetDir(i) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = st.offsetDir == i, onClick = { st.setOffsetDir(i) })
                                Text(dirs[i])
                            }
                        }
                        Text("Uzaklık", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        val dists = listOf("Yakın", "Orta", "Uzak")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (i in dists.indices) {
                                Row(
                                    Modifier.clickable { st.setOffsetDist(i) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = st.offsetDist == i, onClick = { st.setOffsetDist(i) })
                                    Text(dists[i])
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dlg = EDlg.NONE }) { Text("Tamam") } },
            )
        }
        EDlg.GOTO -> {
            AlertDialog(
                onDismissRequest = { dlg = EDlg.NONE },
                title = { Text("Sayfaya git") },
                text = {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(56.dp),
                        modifier = Modifier.heightIn(max = 320.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items((1..st.pageCount).toList()) { n ->
                            val sel = n - 1 == st.currentPage
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (sel) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable {
                                        st.goToPage(n - 1)
                                        dlg = EDlg.NONE
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Text(n.toString()) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dlg = EDlg.NONE }) { Text("Kapat") } },
            )
        }
        EDlg.DELETE_PAGE -> AlertDialog(
            onDismissRequest = { dlg = EDlg.NONE },
            title = { Text("Sayfa silinsin mi?") },
            text = {
                Text(
                    if (st.pageCount <= 1) "Bu tek sayfa; içeriği temizlenecek."
                    else "Sayfa " + (st.currentPage + 1) + " ve üzerindeki çizimler silinecek."
                )
            },
            confirmButton = { TextButton(onClick = { st.deleteCurrentPage(); dlg = EDlg.NONE }) { Text("Sil") } },
            dismissButton = { TextButton(onClick = { dlg = EDlg.NONE }) { Text("Vazgeç") } },
        )
        EDlg.RECORDINGS -> RecordingsDialog(st) { dlg = EDlg.NONE }
    }

    st.textDialog?.let { d ->
        var text by remember(d) { mutableStateOf(d.item?.text ?: "") }
        val fr = remember { FocusRequester() }
        LaunchedEffect(d) {
            kotlinx.coroutines.delay(150)
            try {
                fr.requestFocus()
            } catch (e: Exception) {
                // odak verilemezse klavye elle açılır
            }
        }
        AlertDialog(
            onDismissRequest = { st.textDialog = null },
            title = { Text(if (d.item == null) "Metin ekle" else "Metni düzenle") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().focusRequester(fr),
                )
            },
            confirmButton = { TextButton(onClick = { st.commitText(d, text) }) { Text("Tamam") } },
            dismissButton = {
                Row {
                    if (d.item != null) TextButton(onClick = { st.deleteText(d) }) { Text("Sil") }
                    TextButton(onClick = { st.textDialog = null }) { Text("İptal") }
                }
            },
        )
    }
}

@Composable
private fun TemplateDialog(title: String, onDismiss: () -> Unit, onPick: (Template) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TextButton(onClick = { onPick(Template.BLANK) }, Modifier.fillMaxWidth()) { Text("Boş") }
                TextButton(onClick = { onPick(Template.LINED) }, Modifier.fillMaxWidth()) { Text("Çizgili") }
                TextButton(onClick = { onPick(Template.GRID) }, Modifier.fillMaxWidth()) { Text("Kareli") }
                TextButton(onClick = { onPick(Template.DOT) }, Modifier.fillMaxWidth()) { Text("Noktalı") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}

@Composable
private fun RecordingsDialog(st: EditorState, onDismiss: () -> Unit) {
    val player = remember { AudioPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    val version = st.recVersion
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ses kayıtları") },
        text = {
            if (st.note.recordings.isEmpty()) {
                Text("Bu notta henüz ses kaydı yok. Üstteki mikrofon simgesiyle dersi kaydedebilirsin.")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(st.note.recordings.toList(), key = { it.file + version }) { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { player.toggle(st.recordingFile(r)) }) {
                                Icon(
                                    if (player.playing == r.file) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    "Oynat",
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(r.title)
                                Text(
                                    fmtDur(r.durationMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                if (player.playing == r.file) player.stop()
                                st.deleteRecording(r)
                            }) { Icon(Icons.Filled.Delete, "Sil") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}

// =========================================================================
// Çizim yüzeyi
// =========================================================================

@Composable
fun InkCanvas(st: EditorState, modifier: Modifier) {
    val dark = isSystemInDarkTheme()
    val bg = if (dark) Color(0xFF23262E) else Color(0xFFD9DDE6)
    st.animScope = rememberCoroutineScope()
    Canvas(
        modifier = modifier
            .onSizeChanged { st.onViewSize(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(st) { inkGestures(st) },
    ) {
        @Suppress("UNUSED_VARIABLE")
        val tick = st.redraw
        val sc = st.scale
        val ox = st.offX
        val oy = st.offY
        drawRect(bg)
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            c.save()
            c.translate(ox, oy)
            c.scale(sc, sc)
            val top = -oy / sc
            val bottom = (size.height - oy) / sc
            val pages = st.note.pages
            for (i in pages.indices) {
                val t = st.topOf(i)
                val p = pages[i]
                if (t + p.height < top || t > bottom) continue
                c.save()
                c.translate(0f, t)
                st.renderer.drawPage(c, p, st.bitmaps.get(p))
                c.restore()
            }
            c.restore()
        }
        val tip = st.penCursor
        if (tip != null) drawPenCursor(tip, st.offsetDir == 2, st.tool == com.kalemnot.app.data.ToolType.ERASER, if (st.tool == com.kalemnot.app.data.ToolType.HIGHLIGHTER) st.hlColor else st.penColor)
    }
}

/** Parmakla çizerken: ucu çizim noktasında olan, yukarı-sağa uzanan kalem imleci. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPenCursor(tip: androidx.compose.ui.geometry.Offset, left: Boolean, eraser: Boolean, argb: Int) {
    val u = this.density
    if (eraser) {
        drawCircle(Color(0x22000000), 16f * u, tip)
        drawCircle(Color(0xFF444444), 16f * u, tip, style = androidx.compose.ui.graphics.drawscope.Stroke(2f * u))
        return
    }
    val a = Math.toRadians(if (left) -118.0 else -62.0)
    val dx = Math.cos(a).toFloat()
    val dy = Math.sin(a).toFloat()
    val nx = -dy
    val ny = dx
    val coneLen = 16f * u
    val bodyLen = 62f * u
    val half = 6f * u
    fun pt(along: Float, side: Float) =
        androidx.compose.ui.geometry.Offset(tip.x + dx * along + nx * side, tip.y + dy * along + ny * side)
    val body = androidx.compose.ui.graphics.Path().apply {
        val p1 = pt(coneLen, -half)
        val p2 = pt(bodyLen, -half)
        val p3 = pt(bodyLen, half)
        val p4 = pt(coneLen, half)
        moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); lineTo(p4.x, p4.y); close()
    }
    val cone = androidx.compose.ui.graphics.Path().apply {
        val p1 = pt(coneLen, -half)
        val p4 = pt(coneLen, half)
        moveTo(tip.x, tip.y); lineTo(p1.x, p1.y); lineTo(p4.x, p4.y); close()
    }
    drawPath(body, Color(argb or 0xFF000000.toInt()))
    drawPath(cone, Color(0xFFE8D3B0))
    val line = androidx.compose.ui.graphics.drawscope.Stroke(1.2f * u)
    drawPath(body, Color(0xAA000000), style = line)
    drawPath(cone, Color(0xAA000000), style = line)
    drawCircle(Color(0xFF222222), 2.2f * u, tip)
}

// =========================================================================
// Araç çubuğu
// =========================================================================

@Composable
private fun InkToolbar(st: EditorState, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBtn(st.tool == ToolType.PEN, Icons.Filled.Create, "Kalem") { st.tool = ToolType.PEN }
            ToolBtn(st.tool == ToolType.HIGHLIGHTER, Icons.Filled.Brush, "Fosforlu kalem") { st.tool = ToolType.HIGHLIGHTER }
            ToolBtn(st.tool == ToolType.ERASER, Icons.Filled.Backspace, "Silgi") { st.tool = ToolType.ERASER }
            ToolBtn(st.tool == ToolType.TEXT, Icons.Filled.TextFields, "Metin") { st.tool = ToolType.TEXT }

            if (st.tool != ToolType.ERASER) {
                Divider()
                val colors = if (st.tool == ToolType.HIGHLIGHTER) hlColors else penColors
                for (c in colors) {
                    ColorDot(c, c == st.activeColor) { st.setColor(c) }
                }
                Divider()
                val dots = listOf(6.dp, 10.dp, 15.dp)
                dots.forEachIndexed { i, d ->
                    SizeDot(d, i == st.sizeIdx) { st.setSize(i) }
                }
            }
            Divider()
            ToolBtn(st.fingerDraws, Icons.Filled.TouchApp, "Parmakla çiz") { st.toggleFinger() }
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun ToolBtn(selected: Boolean, icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            desc,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColorDot(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun SizeDot(diameter: androidx.compose.ui.unit.Dp, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface)
        )
    }
}
