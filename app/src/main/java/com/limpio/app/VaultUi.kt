package com.limpio.app

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(browse: BrowseViewModel, onExit: () -> Unit, onGoExplore: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() as? FragmentActivity }
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun exit() { Vault.lock(); onExit() }
    BackHandler {
        when {
            selection.isNotEmpty() -> selection = emptySet()
            Vault.openAlbum != null -> Vault.openAlbum = null
            else -> exit()
        }
    }

    // Si llegaron archivos o carpetas desde el explorador, se guardan en cuanto la carpeta está abierta.
    LaunchedEffect(Vault.unlocked, Vault.pendingImport) {
        val pend = Vault.pendingImport
        if (Vault.unlocked && pend.isNotEmpty() && !Vault.importing) {
            Vault.pendingImport = emptyList()
            Vault.startImport(ctx, pend)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selection.isEmpty()) {
                IconButton(onClick = { if (Vault.openAlbum != null) Vault.openAlbum = null else exit() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                }
                Text(
                    Vault.openAlbum ?: "Carpeta segura", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (Vault.unlocked) IconButton(onClick = { Vault.lock() }) { Icon(Icons.Filled.Lock, "Bloquear") }
            } else {
                IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Filled.Close, "Cancelar") }
                Text("${selection.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val sel = Vault.items.filter { it.id in selection }
                    selection = emptySet()
                    scope.launch {
                        Vault.busy = "Sacando ${sel.size} archivos…"
                        val n = withContext(Dispatchers.IO) { Vault.restore(ctx, sel) }
                        Vault.busy = null
                        Vault.message = "Se restauraron $n archivos a su carpeta original"
                        StorageChanges.bump()
                    }
                }) { Icon(Icons.Filled.Unarchive, "Sacar de la carpeta segura") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Eliminar") }
            }
        }
        Vault.busy?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Vault.message?.let { msg ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).glass(RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(msg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { Vault.message = null }) { Icon(Icons.Filled.Close, "Cerrar aviso") }
            }
        }

        if (!Vault.unlocked) {
            Locked(
                firstTime = !Vault.exists(ctx),
                secure = Vault.deviceSecure(ctx),
                pending = Vault.pendingImport.size,
                onUnlock = { activity?.let { a -> Vault.unlock(a) { } } },
                onSettings = { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            )
            return@Column
        }

        val album = Vault.openAlbum
        val items = if (album != null) Vault.items.filter { it.album == album }.sortedBy { it.name.lowercase() }
        else Vault.items.filter { it.album.isEmpty() }
        val albums = if (album != null) emptyList() else Vault.items.filter { it.album.isNotEmpty() }.groupBy { it.album }.toList().sortedBy { it.first.lowercase() }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (album == null) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().glass().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${Vault.items.size} archivos · ${formatSize(ctx, Vault.items.sumOf { it.size })} · cifrados con AES‑256",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Para agregar: en el explorador mantén presionado un archivo o una carpeta › Más › Mover a carpeta segura. " +
                            "Mantén presionado aquí para sacar o eliminar.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onGoExplore) { Text("Ir al explorador") }
                }
            }
            items(albums, key = { "album:" + it.first }) { (name, list) ->
                val ids = list.map { it.id }.toSet()
                val allSel = ids.isNotEmpty() && selection.containsAll(ids)
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (allSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .combinedClickable(
                            onClick = {
                                if (selection.isNotEmpty()) selection = if (allSel) selection - ids else selection + ids
                                else Vault.openAlbum = name
                            },
                            onLongClick = { selection = if (allSel) selection - ids else selection + ids }
                        )
                        .padding(4.dp)
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        VaultThumb(list.first())
                        Box(
                            Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp)
                        ) { Text("${list.size}", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                        if (allSel) Icon(
                            Icons.Filled.CheckCircle, "Seleccionado", tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(Color.White)
                        )
                    }
                    Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text("Carpeta · ${formatSize(ctx, list.sumOf { it.size })}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(items, key = { it.id }) { item ->
                val selected = item.id in selection
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .combinedClickable(
                            onClick = {
                                if (selection.isNotEmpty()) {
                                    selection = if (selected) selection - item.id else selection + item.id
                                } else scope.launch {
                                    Vault.busy = "Abriendo…"
                                    val f = withContext(Dispatchers.IO) { Vault.openTemp(ctx, item) }
                                    Vault.busy = null
                                    if (f == null) { Vault.message = "No se pudo abrir ${item.name}"; return@launch }
                                    when (item.kind) {
                                        FileKind.IMAGE -> entryOf(f)?.let { e -> browse.openViewer(listOf(e), e) }
                                        FileKind.VIDEO -> Playback.playVideo(listOf(f.absolutePath), 0)
                                        FileKind.AUDIO -> { Music.playFiles(ctx, listOf(f.absolutePath), 0); Music.showNowPlaying = true }
                                        else -> FileOps.open(ctx, f.absolutePath)
                                    }
                                }
                            },
                            onLongClick = { selection = if (selected) selection - item.id else selection + item.id }
                        )
                        .padding(4.dp)
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        VaultThumb(item)
                        if (selected) Icon(
                            Icons.Filled.CheckCircle, "Seleccionado", tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(Color.White)
                        )
                    }
                    Text(item.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(formatSize(ctx, item.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar ${selection.size} archivos para siempre?") },
            text = { Text("Se borrarán de la carpeta segura y no se podrán recuperar.") },
            confirmButton = {
                TextButton(onClick = {
                    val sel = Vault.items.filter { it.id in selection }
                    confirmDelete = false; selection = emptySet()
                    scope.launch { withContext(Dispatchers.IO) { Vault.deleteForever(ctx, sel) } }
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun VaultThumb(item: VaultItem) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { Vault.thumb(ctx, item)?.asImageBitmap() }
    }
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(kindIcon(item.kind), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Locked(firstTime: Boolean, secure: Boolean, pending: Int, onUnlock: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) { Icon(if (firstTime) Icons.Filled.Lock else Icons.Filled.LockOpen, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(18.dp))
        Text(
            if (firstTime) "Crea tu carpeta segura" else "Carpeta segura bloqueada",
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Tus archivos se cifran y se guardan en la memoria privada de Archivos: ninguna otra app, " +
                "galería ni explorador puede verlos. Se abre solo con tu huella (o tu PIN).",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (pending > 0) {
            Spacer(Modifier.height(10.dp))
            Text("$pending archivos esperan a guardarse en cuanto la abras.", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(22.dp))
        if (secure) {
            Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Fingerprint, null)
                Spacer(Modifier.width(10.dp))
                Text(if (firstTime) "Crear con mi huella" else "Abrir con mi huella")
            }
        } else {
            Text(
                "Para usarla, primero configura un bloqueo de pantalla y tu huella.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onSettings) { Text("Abrir ajustes de seguridad") }
        }
        Spacer(Modifier.height(22.dp))
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(14.dp)) {
            Text("Importante", fontWeight = FontWeight.SemiBold)
            Text(
                "Si quitas el bloqueo de pantalla del teléfono o desinstalas Archivos, Android borra la llave y el " +
                    "contenido no se puede recuperar. Antes de hacerlo, saca tus archivos de aquí.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
