package com.kalemnot.app.ink

import android.graphics.Bitmap
import android.util.LruCache
import com.kalemnot.app.data.NoteRepository
import com.kalemnot.app.data.Page
import com.kalemnot.app.data.SourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** PDF sayfası / görsel arka planlarını arka planda yükleyip önbellekte tutar. */
class PageBitmaps(
    repo: NoteRepository,
    noteId: String,
    private val scope: CoroutineScope,
    private val onLoaded: () -> Unit,
) {
    private val loader = SourceLoader(repo.dir(noteId))
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private var closed = false

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 5).coerceAtMost(256L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Hazırsa bitmap'i verir; değilse yüklemeyi başlatır ve null döner. Ana iş parçacığından çağrılır. */
    fun get(page: Page): Bitmap? {
        if (closed) return null
        val src = page.src ?: return null
        val key = "$src#${page.pdfIndex}"
        val hit = cache.get(key)
        if (hit != null) return hit
        if (failed.contains(key)) return null
        if (pending.add(key)) {
            val width = if (page.pdfIndex >= 0) 1700 else 2000
            val copy = Page(page.id, page.template, page.src, page.pdfIndex, page.width, page.height)
            scope.launch(dispatcher) {
                val bmp = try {
                    loader.load(copy, width)
                } catch (e: Throwable) {
                    null
                }
                withContext(Dispatchers.Main) {
                    pending.remove(key)
                    if (bmp != null && !closed) {
                        cache.put(key, bmp)
                        onLoaded()
                    } else if (bmp == null) {
                        failed.add(key)
                    }
                }
            }
        }
        return null
    }

    fun close() {
        closed = true
        executor.execute { loader.close() }
        executor.shutdown()
        cache.evictAll()
    }
}
