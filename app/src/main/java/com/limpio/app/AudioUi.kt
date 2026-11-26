package com.limpio.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File

val ColorHasAudio = Color(0xFF34C759)
val ColorNoAudio = Color(0xFFFF3B30)
val ColorSilent = Color(0xFFFF9500)

/** Indicador pequeño sobre la miniatura de un video. */
@Composable
fun AudioBadge(state: AudioState?, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val (icon, color, desc) = when (state) {
        AudioState.HAS -> Triple(Icons.AutoMirrored.Filled.VolumeUp, Color(0xCC000000), "Con audio")
        AudioState.NONE -> Triple(Icons.AutoMirrored.Filled.VolumeOff, ColorNoAudio, "Sin pista de audio")
        AudioState.SILENT -> Triple(Icons.AutoMirrored.Filled.VolumeMute, ColorSilent, "Audio en silencio")
        else -> return
    }
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(size * 0.66f), tint = Color.White)
    }
}

/** Resumen de audio de los videos de esta vista, con filtros y acceso a la revisión. */
@Composable
fun AudioSummaryCard(vm: BrowseViewModel) {
    val videos = vm.entries.filter { it.kind == FileKind.VIDEO }
    if (videos.isEmpty()) return
    val st = videos.map { AudioScan.states[it.path] }
    val has = st.count { it == AudioState.HAS }
    val none = st.count { it == AudioState.NONE }
    val silent = st.count { it == AudioState.SILENT }
    val checked = st.count { it != null }
    val muted = none + silent
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .glass(RoundedCornerShape(18.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Audio de ${videos.size} videos", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (checked < videos.size) Text(
                "Revisando $checked de ${videos.size}…",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        // Barra proporcional
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))) {
            listOf(has to ColorHasAudio, silent to ColorSilent, none to ColorNoAudio).forEach { (n, c) ->
                if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(c))
            }
            val pending = videos.size - has - silent - none
            if (pending > 0) Spacer(Modifier.weight(pending.toFloat()))
        }
        if (checked < videos.size) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(ColorHasAudio, "$has con audio")
            Legend(ColorSilent, "$silent en silencio")
            Legend(ColorNoAudio, "$none sin pista")
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf<Pair<AudioState?, String>>(
                null to "Todos", AudioState.HAS to "Con audio", AudioState.NONE to "Sin pista", AudioState.SILENT to "En silencio"
            ).forEach { (f, l) ->
                FilterChip(selected = vm.audioFilter == f, onClick = { vm.audioFilter = f }, label = { Text(l) })
            }
        }
        if (muted > 0) {
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { vm.openAudioReview() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ColorNoAudio, contentColor = Color.White)
            ) { Text("Revisar $muted ${if (muted == 1) "video" else "videos"} sin audio") }
        }
    }
}

@Composable
private fun Legend(c: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Revisión de videos sin audio o con audio en silencio, para borrarlos (permanente). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioReviewScreen(vm: BrowseViewModel) {
    BackHandler { vm.audioReview = false }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var only by remember { mutableStateOf<AudioState?>(null) }
    val muted = vm.mutedVideos()
    val shown = muted.filter { only == null || AudioScan.states[it.path] == only }
    val items = shown.map { e ->
        CleanItem(
            path = e.path, size = e.size, isDir = false, lastModified = e.modified, preselected = true,
            note = AudioScan.states[e.path]?.label + " · " + File(e.path).parentFile?.name.orEmpty()
        )
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Videos sin audio") },
                navigationIcon = { IconButton(onClick = { vm.audioReview = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = only == null, onClick = { only = null }, label = { Text("Todos (${muted.size})") })
                FilterChip(
                    selected = only == AudioState.NONE, onClick = { only = AudioState.NONE },
                    label = { Text("Sin pista (${muted.count { AudioScan.states[it.path] == AudioState.NONE }})") }
                )
                FilterChip(
                    selected = only == AudioState.SILENT, onClick = { only = AudioState.SILENT },
                    label = { Text("En silencio (${muted.count { AudioScan.states[it.path] == AudioState.SILENT }})") }
                )
            }
            ReviewList(
                header = "\"Sin pista\": el video no tiene audio. \"En silencio\": tiene pista, pero se escuchó silencio al inicio, en medio y al final.",
                items = items,
                selected = vm.audioSelection,
                onToggle = { p -> vm.audioSelection = if (p in vm.audioSelection) vm.audioSelection - p else vm.audioSelection + p },
                onSetAll = { on -> vm.audioSelection = if (on) vm.audioSelection + items.map { it.path } else vm.audioSelection - items.map { it.path }.toSet() },
                grouped = false,
                destructive = true,
                actionLabel = { n, size -> "Eliminar $n · ${formatSize(ctx, size)}" },
                confirmTitle = { n -> "¿Eliminar $n ${if (n == 1) "video" else "videos"}?" },
                confirmText = { size -> "Se borrarán para siempre (${formatSize(ctx, size)}). No hay papelera." },
                onConfirm = { vm.deleteMuted(items.map { it.path }.filter { it in vm.audioSelection }) },
            )
        }
    }
}
