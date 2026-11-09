package com.kalemnot.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kalemnot.app.data.BackupManager
import com.kalemnot.app.data.Note
import com.kalemnot.app.data.NoteRepository
import com.kalemnot.app.data.NoteSummary
import com.kalemnot.app.data.PdfExporter
import com.kalemnot.app.data.Prefs
import com.kalemnot.app.data.Template
import com.kalemnot.app.ink.EditorState
import com.kalemnot.app.ink.UiBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class AppViewModel(app: Application) : AndroidViewModel(app), UiBridge {
    val repo = NoteRepository(app)
    val prefs = Prefs(app)

    var notes by mutableStateOf<List<NoteSummary>>(emptyList())
    var editor by mutableStateOf<EditorState?>(null)
    var busyMsg by mutableStateOf<String?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    var lastBackup by mutableLongStateOf(prefs.lastBackup)
    var hasBackupTarget by mutableStateOf(prefs.backupUri != null)
    var autoBackup by mutableStateOf(prefs.autoBackup)

    init {
        refresh()
    }

    override fun busy(msg: String?) {
        busyMsg = msg
    }

    override fun message(msg: String) {
        messages.tryEmit(msg)
    }

    fun refresh() {
        viewModelScope.launch {
            notes = withContext(repo.io) { repo.listSummaries() }
        }
    }

    // ---------- Notlar ----------

    fun newNote(template: Template) {
        viewModelScope.launch {
            val n = withContext(repo.io) { repo.createEmpty("Yeni not", template) }
            openLoaded(n)
        }
    }

    fun open(id: String) {
        viewModelScope.launch {
            val n = withContext(repo.io) { repo.load(id) }
            if (n == null) message("Not açılamadı") else openLoaded(n)
        }
    }

    private fun openLoaded(n: Note) {
        editor = EditorState(n, repo, prefs, viewModelScope, this)
    }

    fun closeEditor() {
        val ed = editor ?: return
        editor = null
        val job = ed.close()
        viewModelScope.launch {
            job.join()
            refresh()
        }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch {
            withContext(repo.io) { repo.delete(id) }
            refresh()
        }
    }

    fun renameNote(id: String, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            withContext(repo.io) { repo.updateMeta(id, t, null) }
            refresh()
        }
    }

    fun recolorNote(id: String, color: Int) {
        viewModelScope.launch {
            withContext(repo.io) { repo.updateMeta(id, null, color) }
            refresh()
        }
    }

    fun shareNote(id: String, ctx: Context) {
        viewModelScope.launch {
            busy("PDF hazırlanıyor…")
            try {
                val file = withContext(repo.io) {
                    val n = repo.load(id) ?: throw IllegalStateException("not yok")
                    PdfExporter.export(ctx.applicationContext, repo.dir(id), n.title, n.pages)
                }
                busy(null)
                PdfExporter.share(ctx, file)
            } catch (e: Throwable) {
                busy(null)
                message("PDF oluşturulamadı")
            }
        }
    }

    // ---------- İçe aktarma ----------

    fun importUris(uris: List<Uri>, onDone: () -> Unit = {}) {
        val ed = editor
        val job: Job = if (ed != null) ed.importUris(uris) else importAsNewNote(uris)
        job.invokeOnCompletion { onDone() }
    }

    private fun importAsNewNote(uris: List<Uri>): Job = viewModelScope.launch {
        busy("İçe aktarılıyor…")
        val n = withContext(repo.io) { repo.createFromUris(uris) }
        busy(null)
        if (n == null) {
            message("Desteklenen dosya bulunamadı (PDF veya görsel olmalı)")
        } else {
            openLoaded(n)
            refresh()
        }
    }

    // ---------- Yedekleme ----------

    fun backupToNew(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            // Bazı sağlayıcılar kalıcı izin vermez; yine de bu seferlik yedeklenir.
        }
        prefs.backupUri = uri.toString()
        hasBackupTarget = true
        runBackup(uri, false)
    }

    fun quickBackup() {
        val u = prefs.backupUri?.let { Uri.parse(it) }
        if (u == null) {
            message("Önce bir yedek dosyası oluştur")
            return
        }
        runBackup(u, false)
    }

    fun changeAutoBackup(v: Boolean) {
        autoBackup = v
        prefs.autoBackup = v
    }

    private fun writeBackup(uri: Uri): Int {
        val cr = getApplication<Application>().contentResolver
        return try {
            var os: OutputStream? = null
            try {
                os = cr.openOutputStream(uri, "wt")
            } catch (e: Exception) {
                // "wt" desteklenmiyorsa "w" denenir
            }
            if (os == null) os = cr.openOutputStream(uri, "w")
            if (os == null) return -1
            os.use { BackupManager.write(repo.root, it) }
        } catch (e: Exception) {
            -1
        }
    }

    private fun runBackup(uri: Uri, silent: Boolean): Job {
        editor?.flush()
        return viewModelScope.launch {
            if (!silent) busy("Yedekleniyor…")
            val count = withContext(repo.io) { writeBackup(uri) }
            busy(null)
            if (count >= 0) {
                val now = System.currentTimeMillis()
                prefs.lastBackup = now
                lastBackup = now
                if (!silent) message("$count not yedeklendi")
            } else if (!silent) {
                message("Yedekleme başarısız oldu. Yedek dosyasını yeniden seç.")
            }
        }
    }

    fun restoreFrom(uri: Uri) {
        viewModelScope.launch {
            busy("Geri yükleniyor…")
            val cr = getApplication<Application>().contentResolver
            val ids: List<String> = withContext(repo.io) {
                try {
                    val ins = cr.openInputStream(uri)
                    if (ins == null) {
                        emptyList<String>()
                    } else {
                        val restored = ins.use { BackupManager.restore(it, repo.root) }
                        for (id in restored) {
                            repo.thumbFile(id).delete()
                            repo.ensureThumb(id)
                        }
                        restored
                    }
                } catch (e: Exception) {
                    emptyList<String>()
                }
            }
            busy(null)
            refresh()
            message(if (ids.isEmpty()) "Yedek dosyasında not bulunamadı" else "${ids.size} not geri yüklendi")
        }
    }

    /** Uygulama arka plana gidince: bekleyenleri kaydet, gerekirse otomatik yedekle. */
    fun onAppStop() {
        editor?.flush()
        if (prefs.autoBackup) {
            val u = prefs.backupUri?.let { Uri.parse(it) }
            if (u != null && prefs.lastChange > prefs.lastBackup) runBackup(u, true)
        }
    }
}
