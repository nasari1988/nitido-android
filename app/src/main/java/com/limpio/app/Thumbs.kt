package com.limpio.app

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val thumbCache = LruCache<String, ImageBitmap>(300)

fun kindIcon(kind: FileKind?): ImageVector = when (kind) {
    null -> Icons.Filled.Folder
    FileKind.IMAGE -> Icons.Filled.Image
    FileKind.VIDEO -> Icons.Filled.Movie
    FileKind.AUDIO -> Icons.Filled.MusicNote
    FileKind.DOCUMENT -> Icons.Filled.Description
    FileKind.ARCHIVE -> Icons.Filled.FolderZip
    FileKind.APK -> Icons.Filled.Android
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** Miniatura real del archivo: foto, fotograma de video, carátula de canción o ícono de APK. */
@Composable
fun FileThumb(path: String, isDir: Boolean, size: Dp = 52.dp, modifier: Modifier = Modifier.size(size)) {
    val kind = if (isDir) null else FileTypes.kindOf(path)
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Ícono de fondo: se ve mientras carga o si no hay miniatura.
        Icon(
            kindIcon(kind), null,
            tint = if (isDir) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (kind) {
            FileKind.IMAGE, FileKind.VIDEO -> {
                AsyncImage(
                    model = File(path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (kind == FileKind.VIDEO) {
                    Icon(
                        Icons.Filled.PlayArrow, null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(0x88000000))
                            .padding(2.dp)
                    )
                }
            }
            FileKind.AUDIO, FileKind.APK -> {
                val ctx = LocalContext.current
                val bmp by produceState<ImageBitmap?>(thumbCache.get(path), path) {
                    if (value == null) {
                        value = withContext(Dispatchers.IO) { loadSpecial(ctx, path, kind) }
                            ?.also { thumbCache.put(path, it) }
                    }
                }
                bmp?.let {
                    Image(
                        it, null,
                        contentScale = if (kind == FileKind.APK) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(if (kind == FileKind.APK) 6.dp else 0.dp)
                    )
                }
            }
            else -> Unit
        }
    }
}

private fun loadSpecial(ctx: Context, path: String, kind: FileKind): ImageBitmap? = try {
    when (kind) {
        FileKind.AUDIO -> {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(path)
                mmr.embeddedPicture?.let { bytes ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 128) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
                }
            } finally {
                mmr.release()
            }
        }
        FileKind.APK -> {
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, 0)?.applicationInfo?.let { ai ->
                ai.sourceDir = path
                ai.publicSourceDir = path
                ai.loadIcon(pm).toBitmap(128, 128).asImageBitmap()
            }
        }
        else -> null
    }
} catch (e: Exception) {
    null
}
