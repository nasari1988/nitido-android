package com.limpio.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File

/** Visor a pantalla completa: desliza entre fotos/videos, pellizca o toca dos veces para hacer zoom. */
@Composable
fun MediaViewer(vm: BrowseViewModel) {
    val list = vm.viewer ?: return
    val ctx = LocalContext.current
    BackHandler { vm.closeViewer() }
    val pager = rememberPagerState(initialPage = vm.viewerStart) { vm.viewer?.size ?: 0 }
    var chrome by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }
    val current = list.getOrNull(pager.currentPage.coerceAtMost(list.lastIndex))

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { i -> vm.viewer?.getOrNull(i)?.path ?: i },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val e = list.getOrNull(page) ?: return@HorizontalPager
            if (e.kind == FileKind.VIDEO) VideoPage(e) { chrome = !chrome }
            else ZoomableImage(e.path) { chrome = !chrome }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x99000000)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.closeViewer() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cerrar", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(current?.name ?: "", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${pager.currentPage + 1} de ${list.size}" +
                            (current?.let { " · " + formatSize(ctx, it.size) + " · " + formatDate(it.modified) } ?: ""),
                        color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall
                    )
                }
                current?.let { e ->
                    IconButton(onClick = { vm.toggleFavorite(e.path) }) {
                        Icon(
                            if (e.path in vm.favorites) Icons.Filled.Star else Icons.Filled.StarBorder, "Favorito",
                            tint = if (e.path in vm.favorites) Color(0xFFFFB300) else Color.White
                        )
                    }
                }
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
            ) {
                current?.let { e ->
                    ViewerAction(Icons.Filled.Share, "Compartir") { FileOps.share(ctx, listOf(e.path)) }
                    ViewerAction(Icons.AutoMirrored.Filled.OpenInNew, "Abrir con") { FileOps.openWith(ctx, e.path) }
                    ViewerAction(Icons.Filled.Info, "Detalles") { vm.showDetails(e) }
                    ViewerAction(Icons.Filled.Delete, "Eliminar") { confirmDelete = true }
                }
            }
        }
    }

    if (confirmDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar ${current.name}?") },
            text = { Text("Se eliminará de forma permanente: no pasa por la papelera y no se puede recuperar.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(listOf(current.path)) }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ZoomableImage(path: String, onTap: () -> Unit) {
    val ctx = LocalContext.current
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(path) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    }
                )
            }
            .pointerInput(path) {
                // Pellizco y arrastre con zoom; con un dedo y sin zoom, deja deslizar entre fotos.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale <= 1f) Offset.Zero else {
                                val o = offset + event.calculatePan()
                                val maxX = size.width * (scale - 1) / 2
                                val maxY = size.height * (scale - 1) / 2
                                Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(File(path)).size(2560).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            }
        )
    }
}

@Composable
private fun VideoPage(e: Entry, onTap: () -> Unit) {
    val ctx = LocalContext.current
    Box(
        Modifier.fillMaxSize().pointerInput(e.path) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = File(e.path), contentDescription = null,
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
        )
        IconButton(
            onClick = { FileOps.open(ctx, e.path) },
            modifier = Modifier.size(76.dp).clip(CircleShape).background(Color(0x99000000))
        ) {
            Icon(Icons.Filled.PlayArrow, "Reproducir", tint = Color.White, modifier = Modifier.size(48.dp))
        }
    }
}
