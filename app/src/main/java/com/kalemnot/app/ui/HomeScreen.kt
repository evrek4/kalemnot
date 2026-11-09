package com.kalemnot.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kalemnot.app.AppViewModel
import com.kalemnot.app.data.NoteSummary
import com.kalemnot.app.data.Template
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("tr-TR"))
private val dateTimeFmt = SimpleDateFormat("d MMM yyyy HH:mm", Locale.forLanguageTag("tr-TR"))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onPickImport: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    var backupDlg by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<NoteSummary?>(null) }
    var deleteFor by remember { mutableStateOf<NoteSummary?>(null) }
    var colorFor by remember { mutableStateOf<NoteSummary?>(null) }

    val list = vm.notes.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Notlarda ara") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("KalemNot", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searching) query = ""
                        searching = !searching
                    }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Ara")
                    }
                    IconButton(onClick = { backupDlg = true }) {
                        Icon(Icons.Filled.Backup, "Yedekle")
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { newMenu = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Yeni") },
                )
                DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Boş sayfa") },
                        leadingIcon = { Icon(Icons.Filled.Create, null) },
                        onClick = { newMenu = false; vm.newNote(Template.BLANK) },
                    )
                    DropdownMenuItem(
                        text = { Text("Çizgili sayfa") },
                        leadingIcon = { Icon(Icons.Filled.Create, null) },
                        onClick = { newMenu = false; vm.newNote(Template.LINED) },
                    )
                    DropdownMenuItem(
                        text = { Text("Kareli sayfa") },
                        leadingIcon = { Icon(Icons.Filled.Create, null) },
                        onClick = { newMenu = false; vm.newNote(Template.GRID) },
                    )
                    DropdownMenuItem(
                        text = { Text("Noktalı sayfa") },
                        leadingIcon = { Icon(Icons.Filled.Create, null) },
                        onClick = { newMenu = false; vm.newNote(Template.DOT) },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("PDF veya görsel aç…") },
                        leadingIcon = { Icon(Icons.Filled.PictureAsPdf, null) },
                        onClick = { newMenu = false; onPickImport() },
                    )
                }
            }
        },
    ) { pad ->
        if (list.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Create,
                    null,
                    Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    if (vm.notes.isEmpty()) "Henüz notun yok" else "Eşleşen not yok",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Yeni bir not başlat ya da ders PDF'ini / fotoğrafını buraya sürükleyip bırak.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { vm.newNote(Template.LINED) }) { Text("Boş not") }
                    OutlinedButton(onClick = onPickImport) { Text("PDF / görsel aç") }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
            ) {
                items(list, key = { it.id }) { n ->
                    Box {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .combinedClickable(
                                    onClick = { vm.open(n.id) },
                                    onLongClick = { menuFor = n.id },
                                ),
                            shape = RoundedCornerShape(16.dp),
                            elevation = CardDefaults.cardElevation(2.dp),
                        ) {
                            val bmp by produceState<ImageBitmap?>(null, n.id, n.updatedAt) {
                                value = withContext(Dispatchers.IO) {
                                    vm.repo.loadThumb(n.id)?.asImageBitmap()
                                }
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.78f)
                                    .background(Color.White),
                            ) {
                                val b = bmp
                                if (b != null) {
                                    Image(
                                        bitmap = b,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        alignment = Alignment.TopCenter,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .background(noteColor(n.colorIdx))
                                        .align(Alignment.TopCenter),
                                )
                                if (n.recCount > 0) {
                                    Icon(
                                        Icons.Filled.Mic,
                                        null,
                                        Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp),
                                        tint = Color(0xFF5C6BC0),
                                    )
                                }
                            }
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    n.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    dateFmt.format(Date(n.updatedAt)) + " • " + n.pageCount + " sayfa",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        DropdownMenu(expanded = menuFor == n.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(
                                text = { Text("Aç") },
                                onClick = { menuFor = null; vm.open(n.id) },
                            )
                            DropdownMenuItem(
                                text = { Text("Yeniden adlandır") },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                onClick = { menuFor = null; renameFor = n },
                            )
                            DropdownMenuItem(
                                text = { Text("Renk") },
                                leadingIcon = { Icon(Icons.Filled.Palette, null) },
                                onClick = { menuFor = null; colorFor = n },
                            )
                            DropdownMenuItem(
                                text = { Text("PDF olarak paylaş") },
                                leadingIcon = { Icon(Icons.Filled.Share, null) },
                                onClick = { menuFor = null; vm.shareNote(n.id, ctx) },
                            )
                            DropdownMenuItem(
                                text = { Text("Sil") },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                onClick = { menuFor = null; deleteFor = n },
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- Diyaloglar ----

    renameFor?.let { n ->
        var text by remember(n.id) { mutableStateOf(n.title) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Yeniden adlandır") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.renameNote(n.id, text); renameFor = null }) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("İptal") } },
        )
    }

    deleteFor?.let { n ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Not silinsin mi?") },
            text = { Text("“${n.title}” ve içindeki tüm çizimler ve ses kayıtları silinecek. Bu işlem geri alınamaz.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteNote(n.id); deleteFor = null }) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Vazgeç") } },
        )
    }

    colorFor?.let { n ->
        AlertDialog(
            onDismissRequest = { colorFor = null },
            title = { Text("Not rengi") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (rowStart in listOf(0, 4)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (i in rowStart until rowStart + 4) {
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(noteColor(i))
                                        .border(
                                            if (i == n.colorIdx % 8) 3.dp else 1.dp,
                                            if (i == n.colorIdx % 8) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                            CircleShape,
                                        )
                                        .clickable {
                                            vm.recolorNote(n.id, i)
                                            colorFor = null
                                        },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { colorFor = null }) { Text("Kapat") } },
        )
    }

    if (backupDlg) {
        AlertDialog(
            onDismissRequest = { backupDlg = false },
            title = { Text("Yedekleme") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Tüm notlarını tek bir yedek dosyasına (zip) kaydeder. Açılan pencerede Google Drive'ı " +
                            "seçip bir klasör belirle. Sonraki yedeklemeler aynı dosyanın üstüne yazılır.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (vm.lastBackup > 0L) "Son yedek: " + dateTimeFmt.format(Date(vm.lastBackup))
                        else "Henüz yedek alınmadı.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { backupDlg = false; onCreateBackup() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Yeni yedek dosyası oluştur") }
                    OutlinedButton(
                        onClick = { backupDlg = false; vm.quickBackup() },
                        enabled = vm.hasBackupTarget,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Son dosyaya hızlı yedekle") }
                    OutlinedButton(
                        onClick = { backupDlg = false; onRestore() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Yedekten geri yükle") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Uygulamadan çıkarken otomatik yedekle",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Box(Modifier.width(8.dp))
                        Switch(
                            checked = vm.autoBackup && vm.hasBackupTarget,
                            onCheckedChange = { vm.setAutoBackup(it) },
                            enabled = vm.hasBackupTarget,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { backupDlg = false }) { Text("Kapat") } },
        )
    }
}
