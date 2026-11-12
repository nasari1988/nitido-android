package com.limpio.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Comprimir y extraer ZIP. No usa nada de Android, así que se prueba en la compilación. */
object ZipOps {

    /** Comprime archivos y carpetas (con su contenido) en [dest]. */
    fun zip(sources: List<File>, dest: File, onFile: (String) -> Unit = {}): Boolean = try {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zos ->
            for (src in sources) {
                if (src.isDirectory) {
                    src.walkTopDown().forEach { f ->
                        val rel = src.name + "/" + f.relativeTo(src).invariantSeparatorsPath
                        if (f.isDirectory) {
                            zos.putNextEntry(ZipEntry(rel.trimEnd('/') + "/"))
                            zos.closeEntry()
                        } else {
                            addFile(zos, f, rel)
                            onFile(f.name)
                        }
                    }
                } else {
                    addFile(zos, src, src.name)
                    onFile(src.name)
                }
            }
        }
        true
    } catch (e: Exception) {
        dest.delete()
        false
    }

    private fun addFile(zos: ZipOutputStream, f: File, name: String) {
        zos.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
        BufferedInputStream(FileInputStream(f)).use { it.copyTo(zos, 64 * 1024) }
        zos.closeEntry()
    }

    /**
     * Extrae [zipFile] dentro de [destDir]. Rechaza entradas que intenten salir de la
     * carpeta destino (p. ej. "../../algo"), un truco conocido de archivos ZIP maliciosos.
     * Devuelve cuántos archivos extrajo, o -1 si falló.
     */
    fun unzip(zipFile: File, destDir: File, onFile: (String) -> Unit = {}): Int = try {
        destDir.mkdirs()
        val base = destDir.canonicalPath + File.separator
        var count = 0
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(destDir, entry.name)
                if (!out.canonicalPath.startsWith(base) && out.canonicalPath != destDir.canonicalPath) {
                    throw SecurityException("Entrada fuera de la carpeta: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(out)).use { zis.copyTo(it, 64 * 1024) }
                    if (entry.time > 0) out.setLastModified(entry.time)
                    count++
                    onFile(out.name)
                }
                zis.closeEntry()
            }
        }
        count
    } catch (e: Exception) {
        -1
    }
}
