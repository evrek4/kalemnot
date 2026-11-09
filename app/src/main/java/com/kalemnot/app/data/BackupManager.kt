package com.kalemnot.app.data

import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Tüm notları tek bir zip dosyasına yazar / zip'ten geri yükler. Arka planda çağrılmalıdır. */
object BackupManager {
    fun write(root: File, out: OutputStream): Int {
        var count = 0
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (d in dirs) {
                var any = false
                for (f in d.walkTopDown()) {
                    if (!f.isFile || f.name == "thumb.png" || f.name.endsWith(".tmp")) continue
                    val rel = d.name + "/" + f.relativeTo(d).path.replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    any = true
                }
                if (any) count++
            }
        }
        return count
    }

    /** Yüklenen not kimliklerini döndürür. */
    fun restore(input: InputStream, root: File): List<String> {
        val ids = LinkedHashSet<String>()
        val rootCanon = root.canonicalFile
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                if (name.startsWith("/") || name.contains("..")) continue
                val dest = File(root, name).canonicalFile
                if (!dest.path.startsWith(rootCanon.path + File.separator)) continue
                dest.parentFile?.mkdirs()
                dest.outputStream().use { zip.copyTo(it) }
                ids.add(name.substringBefore('/'))
            }
        }
        return ids.filter { File(root, "$it/note.json").exists() }
    }
}
