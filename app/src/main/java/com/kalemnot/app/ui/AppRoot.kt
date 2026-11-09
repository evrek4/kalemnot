package com.kalemnot.app.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kalemnot.app.AppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRoot(vm: AppViewModel, activity: ComponentActivity) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snack.showSnackbar(it) }
    }

    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.backupToNew(uri)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restoreFrom(uri)
    }

    val driveConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) vm.syncDrive(true)
        else vm.message("Drive izni verilmedi")
    }
    val pending = vm.pendingConsent
    LaunchedEffect(pending) {
        if (pending != null) {
            vm.consentHandled()
            try {
                driveConsent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            } catch (e: Exception) {
                vm.message("İzin ekranı açılamadı")
            }
        }
    }

    BackHandler(enabled = vm.editor != null) { vm.closeEditor() }

    // Başka uygulamadan (ör. dosya yöneticisi, bölünmüş ekranda) sürükleyip bırakma
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val de = event.toAndroidDragEvent()
                val clip = de.clipData ?: return false
                val uris = ArrayList<Uri>()
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uris.add(it) }
                }
                if (uris.isEmpty()) return false
                val perms = try {
                    activity.requestDragAndDropPermissions(de)
                } catch (e: Exception) {
                    null
                }
                vm.importUris(uris) { perms?.release() }
                return true
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    event.mimeTypes().any {
                        it == "application/pdf" || it.startsWith("image/") ||
                            it == "text/uri-list" || it == "application/octet-stream"
                    }
                },
                target = dropTarget,
            )
    ) {
        val ed = vm.editor
        if (ed == null) {
            HomeScreen(
                vm = vm,
                onPickImport = { pickImport.launch(arrayOf("application/pdf", "image/*")) },
                onCreateBackup = {
                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    createBackup.launch("KalemNot-yedek-$stamp.zip")
                },
                onRestore = { restore.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            )
        } else {
            EditorScreen(
                vm = vm,
                st = ed,
                onPickImport = { pickImport.launch(arrayOf("application/pdf", "image/*")) },
            )
        }

        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))

        val busy = vm.busyMsg
        if (busy != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Box(Modifier.width(16.dp))
                        Text(busy)
                    }
                }
            }
        }
    }
}
