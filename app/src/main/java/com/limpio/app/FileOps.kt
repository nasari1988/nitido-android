package com.limpio.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File

/** Cada vez que la app cambia archivos sube este número, para que las otras pestañas se actualicen. */
object StorageChanges {
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun bump() { version++ }
}

object FileOps {

    fun isInside(child: String, parent: String): Boolean =
        child == parent || child.startsWith("$parent/")

    /** "foto.jpg" → "foto (1).jpg" si ya existe. */
    fun uniqueTarget(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val f = File(dir, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }

    /** Mueve dentro del mismo almacenamiento (instantáneo); si falla, copia y borra. */
    fun move(src: File, destDir: File): File? {
        if (!destDir.exists() && !destDir.mkdirs()) return null
        val target = uniqueTarget(destDir, src.name)
        if (src.renameTo(target)) return target
        return try {
            if (src.copyRecursively(target, overwrite = false)) {
                target.setLastModified(src.lastModified())
                src.deleteRecursively()
                target
            } else null
        } catch (e: Exception) {
            target.deleteRecursively()
            null
        }
    }

    fun copy(src: File, destDir: File): File? {
        if (!destDir.exists() && !destDir.mkdirs()) return null
        val target = uniqueTarget(destDir, src.name)
        return try {
            if (src.copyRecursively(target, overwrite = false)) {
                target.setLastModified(src.lastModified())
                target
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** Borrado permanente: no pasa por ninguna papelera. */
    fun deletePermanently(f: File): Boolean =
        if (f.isDirectory) f.deleteRecursively() else (f.delete() || !f.exists())

    /** Avisa a la galería y reproductores de los cambios (archivos nuevos o eliminados). */
    fun rescan(context: Context, paths: Collection<String>) {
        val all = LinkedHashSet<String>()
        for (p in paths) {
            val f = File(p)
            if (f.isDirectory) {
                f.walkTopDown().filter { it.isFile }.take(5000).forEach { all += it.absolutePath }
            } else all += p
        }
        if (all.isNotEmpty()) {
            MediaScannerConnection.scanFile(context.applicationContext, all.toTypedArray(), null, null)
        }
    }

    fun open(context: Context, path: String) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, FileTypes.mimeOf(path))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No hay una app para abrir este archivo", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo abrir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
