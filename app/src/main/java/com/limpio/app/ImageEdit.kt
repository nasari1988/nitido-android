package com.limpio.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File

object ImageEdit {
    /**
     * Gira 90° a la derecha. En JPG solo cambia la orientación guardada en la foto
     * (sin perder calidad); en otros formatos vuelve a guardar la imagen girada.
     */
    fun rotate(path: String): Boolean = try {
        val ext = FileTypes.ext(path)
        if (ext == "jpg" || ext == "jpeg") {
            val exif = ExifInterface(path)
            exif.rotate(90)
            exif.saveAttributes()
            true
        } else {
            val src = BitmapFactory.decodeFile(path) ?: throw IllegalStateException("No se pudo leer")
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(90f) }, true)
            val format = when (ext) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }
            val tmp = File("$path.tmp")
            tmp.outputStream().use { rotated.compress(format, 95, it) }
            src.recycle(); rotated.recycle()
            tmp.renameTo(File(path)) || (File(path).delete() && tmp.renameTo(File(path)))
        }
    } catch (e: Exception) {
        false
    }

    /** Abre el selector del sistema: fondo de pantalla, foto de contacto, etc. */
    fun setAs(ctx: Context, path: String) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(path))
            val intent = Intent(Intent.ACTION_ATTACH_DATA)
                .setDataAndType(uri, FileTypes.mimeOf(path))
                .putExtra("mimeType", FileTypes.mimeOf(path))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(intent, "Usar como").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "No se pudo abrir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
