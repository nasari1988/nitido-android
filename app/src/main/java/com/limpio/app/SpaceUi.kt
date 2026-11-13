package com.limpio.app

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class SpaceNode(val path: String, val name: String, val size: Long, val isDir: Boolean, val count: Int = 1)

private val SPACE_COLORS = listOf(
    0xFFD6363B, 0xFF7B3FBF, 0xFF1E6FD9, 0xFF0B7A5F, 0xFFC2610C, 0xFF2E8B3E, 0xFF00838F, 0xFF795548, 0xFF8E24AA, 0xFF3949AB, 0xFFAD1457
).map { Color(it) }

class SpaceViewModel(app: Application) : AndroidViewModel(app) {
    var sizes by mutableStateOf<Map<String, Long>?>(null)
        private set
    var scanning by mutableStateOf(false)
        private set
    var counted by mutableIntStateOf(0)
        private set
    var dir by mutableStateOf(Protection.rootPath)
        private set
    var children by mutableStateOf<List<SpaceNode>>(emptyList())
        private set

    fun analyze(force: Boolean = false) {
        if (scanning || (sizes != null && !force)) return
        scanning = true
        counted = 0
        viewModelScope.launch {
            val n = AtomicInteger()
            val job = launch { while (true) { counted = n.get(); kotlinx.coroutines.delay(150) } }
            val map = withContext(Dispatchers.IO) {
                SpaceAnalyzer.folderSizes(File(Protection.rootPath), skip = { Protection.isHiddenSystem(it.absolutePath) }) { n.incrementAndGet() }
            }
            job.cancel()
            sizes = map
            scanning = false
            open(dir)
        }
    }

    fun open(path: String) {
        dir = path
        val map = sizes ?: return
        viewModelScope.launch {
            children = withContext(Dispatchers.IO) {
                File(path).listFiles().orEmpty()
                    .filterNot { Protection.isHiddenSystem(it.absolutePath) }
                    .map { f ->
                        if (f.isDirectory) SpaceNode(f.absolutePath, f.name, map[f.absolutePath] ?: 0, true)
                        else SpaceNode(f.absolutePath, f.name, f.length(), false)
                    }
                    .filter { it.size > 0 }
                    .sortedByDescending { it.size }
            }
        }
    }

    fun up(): Boolean {
        if (dir == Protection.rootPath) return false
        open(File(dir).parent ?: Protection.rootPath)
        return true
    }
}

@Composable
fun SpaceScreen(vm: SpaceViewModel, onExit: () -> Unit, onOpenFolder: (String) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.analyze() }
    BackHandler { if (!vm.up()) onExit() }
    val total = vm.sizes?.get(vm.dir) ?: 0L

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (!vm.up()) onExit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
            Column(Modifier.weight(1f)) {
                Text("Análisis de espacio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Almacenamiento/" + Protection.relative(vm.dir) + if (vm.sizes != null) " · ${formatSize(ctx, total)}" else "",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { vm.analyze(force = true) }) { Icon(Icons.Filled.Refresh, "Volver a analizar") }
        }
        if (vm.scanning || vm.sizes == null) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Midiendo carpetas… ${vm.counted} archivos")
            }
            return@Column
        }
        val shown = vm.children.take(12)
        val rest = vm.children.drop(12)
        val blocks = shown + if (rest.isNotEmpty()) listOf(SpaceNode("", "Otros (${rest.size})", rest.sumOf { it.size }, false, rest.size)) else emptyList()

        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                BoxWithConstraints(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(18.dp))) {
                    val density = LocalDensity.current
                    val wPx = with(density) { maxWidth.toPx() }
                    val hPx = with(density) { maxHeight.toPx() }
                    val rects = Treemap.squarify(blocks.map { it.size.toDouble() }, 0f, 0f, wPx, hPx)
                    blocks.forEachIndexed { i, node ->
                        val r = rects[i]
                        val color = if (node.path.isEmpty()) Color(0xFF8A929C) else SPACE_COLORS[i % SPACE_COLORS.size]
                        Box(
                            Modifier
                                .offset(with(density) { r.x.toDp() }, with(density) { r.y.toDp() })
                                .size(with(density) { r.w.toDp() }, with(density) { r.h.toDp() })
                                .padding(2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(color)
                                .clickable(enabled = node.path.isNotEmpty()) {
                                    if (node.isDir) vm.open(node.path) else FileOps.open(ctx, node.path)
                                }
                                .padding(6.dp),
                            contentAlignment = Alignment.BottomStart
                        ) {
                            if (r.w > 140 && r.h > 90) {
                                Column {
                                    Text(node.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                    Text(formatSize(ctx, node.size), color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                Text(
                    "Toca un bloque para entrar. Los colores grandes son lo que más ocupa.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            items(vm.children, key = { it.path }) { node ->
                val pct = if (total > 0) node.size.toFloat() / total else 0f
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp))
                        .clickable { if (node.isDir) vm.open(node.path) else FileOps.open(ctx, node.path) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FileThumb(node.path, node.isDir, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(node.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(formatSize(ctx, node.size), fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))) {
                            Box(Modifier.fillMaxWidth(pct.coerceIn(0.01f, 1f)).height(6.dp).background(MaterialTheme.colorScheme.primary))
                        }
                        Text("${"%.1f".format(pct * 100)} %", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (node.isDir) {
                        IconButton(onClick = { onOpenFolder(node.path) }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Abrir en el explorador")
                        }
                    }
                }
            }
        }
    }
}
