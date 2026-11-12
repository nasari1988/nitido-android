package com.limpio.app

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Colores del vidrio: relleno translúcido, versión casi opaca (menús, diálogos) y borde de luz. */
data class GlassColors(val fill: Color, val strong: Color, val border: Color, val dark: Boolean)

val LocalGlass = staticCompositionLocalOf {
    GlassColors(Color(0x8CFFFFFF), Color(0xF7F8FAFC), Color(0xBFFFFFFF), false)
}

/** Tema de Archivos: sigue el modo claro/oscuro del teléfono y sus colores (Android 12+). */
@Composable
fun ArchivosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val base = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF5FD4B0), onPrimary = Color(0xFF00382A))
        else -> lightColorScheme(primary = Color(0xFF0B7A5F))
    }
    val glass = if (dark) {
        GlassColors(Color(0x1FFFFFFF), Color(0xF21B2028), Color(0x29FFFFFF), true)
    } else {
        GlassColors(Color(0x99FFFFFF), Color(0xF7F8FAFC), Color(0xCCFFFFFF), false)
    }
    // Las superficies quedan transparentes para que se vea el fondo; las tarjetas son de vidrio;
    // menús y diálogos son casi opacos para que se lean bien.
    val scheme = base.copy(
        background = Color.Transparent,
        surface = Color.Transparent,
        surfaceVariant = glass.fill,
        surfaceContainerLowest = glass.fill,
        surfaceContainerLow = glass.fill,
        surfaceContainerHighest = glass.fill,
        surfaceContainer = glass.strong,
        surfaceContainerHigh = glass.strong,
        primaryContainer = base.primaryContainer.copy(alpha = 0.78f),
        secondaryContainer = base.secondaryContainer.copy(alpha = 0.85f),
    )
    CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Fondo de la app: tu fondo de pantalla, desenfocado; si no se puede leer, manchas de color. */
@Composable
fun GlassBackground() {
    val ctx = LocalContext.current
    val glass = LocalGlass.current
    val wallpaper by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.IO) { blurredWallpaper(ctx) }
    }
    Box(Modifier.fillMaxSize().background(if (glass.dark) Color(0xFF0D1117) else Color(0xFFEEF2F6))) {
        val w = wallpaper
        if (w != null) {
            Image(
                w, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val blobs = if (glass.dark) {
                listOf(Color(0xFF0FA37F), Color(0xFF3D5BFF), Color(0xFF9B4DFF), Color(0xFFFF7A59))
            } else {
                listOf(Color(0xFF48D1AE), Color(0xFF7AA2FF), Color(0xFFC59BFF), Color(0xFFFFC59A))
            }
            Canvas(Modifier.fillMaxSize()) {
                val spots = listOf(
                    Offset(size.width * 0.1f, size.height * 0.08f),
                    Offset(size.width * 0.95f, size.height * 0.3f),
                    Offset(size.width * 0.05f, size.height * 0.62f),
                    Offset(size.width * 0.9f, size.height * 0.92f),
                )
                spots.forEachIndexed { i, c ->
                    val r = size.width * 0.75f
                    drawCircle(
                        Brush.radialGradient(
                            listOf(blobs[i].copy(alpha = if (glass.dark) 0.5f else 0.7f), Color.Transparent),
                            center = c, radius = r
                        ),
                        radius = r, center = c
                    )
                }
            }
        }
        // Velo para que el texto siempre se lea bien sobre cualquier fondo.
        Box(Modifier.fillMaxSize().background(if (glass.dark) Color(0x80000000) else Color(0x59FFFFFF)))
    }
}

/** Reducir mucho y volver a ampliar con suavizado da un desenfoque fuerte y barato. */
private fun blurredWallpaper(ctx: Context): ImageBitmap? = try {
    val d = WallpaperManager.getInstance(ctx).drawable
    if (d == null) null else {
        val iw = d.intrinsicWidth.coerceAtLeast(1)
        val ih = d.intrinsicHeight.coerceAtLeast(1)
        val tw = (iw / 40).coerceAtLeast(8)
        val th = (ih / 40).coerceAtLeast(8)
        val tiny = d.toBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val mid = Bitmap.createScaledBitmap(tiny, tw * 6, th * 6, true)
        tiny.recycle()
        mid.asImageBitmap()
    }
} catch (e: Exception) {
    null
}

/** Modificador de vidrio para cualquier superficie. */
@Composable
fun Modifier.glass(shape: Shape = RoundedCornerShape(22.dp), strong: Boolean = false): Modifier {
    val g = LocalGlass.current
    return this.clip(shape).background(if (strong) g.strong else g.fill).border(1.dp, g.border, shape)
}

data class NavItem(val icon: ImageVector, val label: String)

/** Barra de pestañas flotante de vidrio. */
@Composable
fun GlassNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(68.dp).glass(RoundedCornerShape(26.dp)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable { onSelect(i) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(width = 58.dp, height = 30.dp).clip(RoundedCornerShape(15.dp))
                            .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            item.icon, null,
                            tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
