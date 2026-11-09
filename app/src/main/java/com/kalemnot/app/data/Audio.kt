package com.kalemnot.app.data

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class AudioRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null

    @Suppress("DEPRECATION")
    fun start(file: File): Boolean {
        return try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            rec = r
            true
        } catch (e: Exception) {
            try { rec?.release() } catch (x: Exception) { }
            rec = null
            false
        }
    }

    fun stop() {
        val r = rec ?: return
        try { r.stop() } catch (e: Exception) { }
        try { r.release() } catch (e: Exception) { }
        rec = null
    }
}

class AudioPlayer {
    private var mp: MediaPlayer? = null
    var playing by mutableStateOf<String?>(null)
        private set

    fun toggle(file: File) {
        if (playing == file.name) {
            stop()
            return
        }
        stop()
        try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.setOnCompletionListener { stop() }
            p.start()
            mp = p
            playing = file.name
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        val p = mp
        mp = null
        playing = null
        if (p != null) {
            try { p.stop() } catch (e: Exception) { }
            try { p.release() } catch (e: Exception) { }
        }
    }
}
