package com.mia.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mia.launcher.data.Store

val GlassTint = Color(0xFF1E1846)
val Muted = Color(0xFFE4DFFF)
val Accent = Color(0xFF8C6CFF)
val Ink = Color(0xFF2B2066)
val Mint = Color(0xFF8EE3C8)

val ShadowText = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1.5f), 8f))

/** Superficie de «vidrio»: translúcida, con borde de luz. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(28.dp), alpha: Float = Store.settings.glass): Modifier =
    this.clip(shape)
        .background(GlassTint.copy(alpha = alpha.coerceIn(0.1f, 0.95f)))
        .border(1.dp, Color.White.copy(alpha = 0.3f), shape)

fun Modifier.glassLight(shape: Shape = RoundedCornerShape(22.dp)): Modifier =
    this.clip(shape)
        .background(Color.White.copy(alpha = 0.14f))
        .border(1.dp, Color.White.copy(alpha = 0.28f), shape)

@Composable
fun MiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondary = Mint,
            surface = Color(0xFF221C4F),
            onSurface = Color.White,
            background = Color(0xFF1B1640),
        ),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp, top = 6.dp))
}

@Composable
fun StatusDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Muted, fontSize = 13.sp)
    }
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
