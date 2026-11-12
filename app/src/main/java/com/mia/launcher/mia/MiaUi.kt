package com.mia.launcher.mia

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mia.launcher.R
import com.mia.launcher.ui.Ink
import com.mia.launcher.ui.Muted
import com.mia.launcher.ui.StatusDot
import com.mia.launcher.ui.glass
import com.mia.launcher.ui.glassLight
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Mia de cuerpo completo con su animación según la pose. */
@Composable
fun MiaFigure(pose: Pose, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxWidth(0.75f)
                .fillMaxHeight(0.8f)
                .align(Alignment.Center)
                .blur(48.dp)
                .background(Brush.radialGradient(listOf(pose.halo, Color.Transparent)), CircleShape)
        )
        Crossfade(targetState = pose, animationSpec = tween(450), label = "pose") { p ->
            val tr = rememberInfiniteTransition(label = "anim")
            val phase by tr.animateFloat(
                0f, 1f, infiniteRepeatable(tween(p.anim.durationMs, easing = LinearEasing), RepeatMode.Restart), label = "phase"
            )
            val s = sin(phase * 2 * PI).toFloat()
            val d = LocalDensity.current.density
            var ty = 0f; var tx = 0f; var rot = 0f; var sc = 1f
            when (p.anim) {
                Anim.RESPIRAR -> { ty = -4f * (s + 1) / 2; sc = 1f + 0.012f * (s + 1) / 2 }
                Anim.BRINCO -> ty = -12f * max(0f, s)
                Anim.TRAVIESA -> rot = 2.5f * s
                Anim.PENSAR -> { ty = -3f * (s + 1) / 2; rot = -2f * (s + 1) / 2 }
                Anim.TEMBLOR -> tx = 2f * s
                Anim.TRISTE -> ty = 5f * (s + 1) / 2
                Anim.ENOJO -> rot = 1.5f * s
                Anim.DORMIR -> ty = 3f * (s + 1) / 2
            }
            Image(
                painter = painterResource(p.res),
                contentDescription = "Mia, ${p.label.lowercase()}",
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomCenter,
                colorFilter = if (p.anim == Anim.DORMIR) ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.7f, 0.7f, 0.8f, 1f) }) else null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = ty * d; translationX = tx * d; rotationZ = rot
                        scaleX = sc; scaleY = sc
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
            )
        }
    }
}

@Composable
fun ListeningWave(modifier: Modifier = Modifier) {
    val colors = listOf(Color(0xFF8C6CFF), Color(0xFFFF8A65), Color(0xFF8EE3C8), Color(0xFFFFE08A))
    val tr = rememberInfiniteTransition(label = "wave")
    Row(modifier.height(40.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(11) { k ->
            val v by tr.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(420 + (k % 3) * 120, delayMillis = k * 40), RepeatMode.Reverse),
                label = "b$k"
            )
            Box(Modifier.width(5.dp).fillMaxHeight(v).clip(RoundedCornerShape(3.dp)).background(colors[k % 4]))
        }
    }
}

data class Suggestion(val label: String, val ask: String)

val MiaSuggestions = listOf(
    Suggestion("Alarma a las 7", "Pon una alarma a las 7"),
    Suggestion("¿Lloverá hoy?", "¿Lloverá hoy?"),
    Suggestion("Cuéntame un chiste", "Cuéntame un chiste"),
    Suggestion("Linterna", "Prende la linterna"),
    Suggestion("Mi batería", "¿Cuánta batería tengo?"),
    Suggestion("Temporizador 5 min", "Pon un temporizador de 5 minutos"),
)

/** Barra para escribirle o hablarle a Mia. */
@Composable
fun MiaInputBar(activity: Activity?, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) MiaController.startListening(activity)
    }
    fun send() {
        if (text.isNotBlank()) { MiaController.handleInput(text, activity); text = "" }
        else if (MiaController.hasMic(ctx)) MiaController.startListening(activity)
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
    Row(
        modifier.fillMaxWidth().height(56.dp).glass(RoundedCornerShape(28.dp)).padding(start = 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text(if (MiaController.listening) MiaController.partial.ifBlank { "Te escucho…" } else "Escríbele a Mia…", color = Muted, fontSize = 15.sp, maxLines = 1)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color.White).clickable { send() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(if (text.isBlank()) R.drawable.ic_mic else R.drawable.ic_send),
                contentDescription = if (text.isBlank()) "Hablar con Mia" else "Enviar a Mia",
                tint = Color(0xFF5B3FD9), modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun MiaBubble(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Color(0xFF1D1A33), fontSize = 15.sp, lineHeight = 21.sp,
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 6.dp))
            .background(Color.White.copy(alpha = 0.93f))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun SuggestionRow(activity: Activity?) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        items(MiaSuggestions) { s ->
            Text(
                s.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.glassLight(RoundedCornerShape(20.dp)).clickable { MiaController.handleInput(s.ask, activity) }
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            )
        }
    }
}

/** Panel izquierdo del launcher: Mia. */
@Composable
fun MiaPanel(activity: Activity?, onOpenSettings: () -> Unit) {
    val pose = MiaController.pose
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mia", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                StatusDot(pose.statusColor, if (MiaController.listening) "Escuchando…" else pose.status)
            }
            Box(Modifier.size(44.dp).glassLight(CircleShape).clickable { onOpenSettings() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Settings, "Ajustes", tint = Color.White)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).glass(RoundedCornerShape(32.dp))) {
            MiaFigure(pose, Modifier.fillMaxSize().padding(top = 12.dp))
            if (MiaController.listening) ListeningWave(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
        }
        Row(
            Modifier.fillMaxWidth().glassLight(RoundedCornerShape(22.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Detectó: ", color = Muted, fontSize = 13.sp)
            Text(MiaController.trigger, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        MiaBubble(MiaController.message)
        SuggestionRow(activity)
        MiaInputBar(activity)
    }
}

@Composable
fun BoxScope.MiaMini(onClick: () -> Unit) {
    Row(
        Modifier.align(Alignment.Center).fillMaxWidth().clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painterResource(MiaController.pose.res), null,
            contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
            modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Mia", color = Color(0xFFC9BEFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(MiaController.message, color = Color.White, fontSize = 14.sp, maxLines = 3, lineHeight = 19.sp)
        }
    }
}

@Composable
fun InkText(text: String) = Text(text, color = Ink)
