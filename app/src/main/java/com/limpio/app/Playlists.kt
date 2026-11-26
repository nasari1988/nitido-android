package com.limpio.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Playlist(val id: String, val name: String, val items: List<String>) {
    val audio: List<String> get() = items.filter { FileTypes.kindOf(it) == FileKind.AUDIO }
    val videos: List<String> get() = items.filter { FileTypes.kindOf(it) == FileKind.VIDEO }
    fun summary(): String = listOfNotNull(
        audio.size.takeIf { it > 0 }?.let { "$it canciones" },
        videos.size.takeIf { it > 0 }?.let { "$it videos" },
    ).joinToString(" · ").ifEmpty { "Vacía" }
}

/** Listas de reproducción (canciones y videos), guardadas en la memoria de la app. */
object Playlists {
    const val FAVORITES = "favoritos"
    var lists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    /** Rutas marcadas con estrella en el explorador (para la lista automática). */
    var favoritePaths by mutableStateOf<Set<String>>(emptySet())
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "listas.json")

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        lists = try {
            val arr = JSONArray(file(ctx).takeIf { it.exists() }?.readText() ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val its = o.getJSONArray("items")
                Playlist(o.getString("id"), o.getString("name"), (0 until its.length()).map { its.getString(it) })
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        lists.forEach { p -> arr.put(JSONObject().put("id", p.id).put("name", p.name).put("items", JSONArray(p.items))) }
        val tmp = File(file(ctx).absolutePath + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }

    /** Todas, empezando por la automática de Favoritos (si tiene algo). */
    fun all(): List<Playlist> {
        val fav = favoritePaths.filter { FileTypes.kindOf(it) == FileKind.AUDIO || FileTypes.kindOf(it) == FileKind.VIDEO }
            .filter { File(it).exists() }.sorted()
        return (if (fav.isNotEmpty()) listOf(Playlist(FAVORITES, "Favoritos", fav)) else emptyList()) + lists
    }

    fun get(id: String) = all().firstOrNull { it.id == id }

    fun create(ctx: Context, name: String, items: List<String> = emptyList()): Playlist {
        load(ctx)
        val p = Playlist(UUID.randomUUID().toString(), name.trim().ifEmpty { "Lista nueva" }, items.distinct())
        lists = lists + p
        save(ctx)
        return p
    }

    fun add(ctx: Context, id: String, paths: List<String>) {
        lists = lists.map { if (it.id == id) it.copy(items = (it.items + paths).distinct()) else it }
        save(ctx)
    }

    fun remove(ctx: Context, id: String, path: String) {
        lists = lists.map { if (it.id == id) it.copy(items = it.items - path) else it }
        save(ctx)
    }

    fun rename(ctx: Context, id: String, name: String) {
        lists = lists.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }
        save(ctx)
    }

    fun delete(ctx: Context, id: String) {
        lists = lists.filterNot { it.id == id }
        save(ctx)
    }

    /** Reproduce una lista: canciones en el reproductor de música, videos en el de video. */
    fun play(ctx: Context, p: Playlist, videos: Boolean, start: String? = null) {
        if (videos) {
            val v = p.videos.filter { File(it).exists() }
            if (v.isNotEmpty()) Playback.playVideo(v, v.indexOf(start).coerceAtLeast(0), "Lista: ${p.name}")
        } else {
            val a = p.audio.filter { File(it).exists() }
            if (a.isNotEmpty()) {
                Music.playFiles(ctx, a, a.indexOf(start).coerceAtLeast(0), "Lista · ${p.name}")
                Music.showNowPlaying = true
            }
        }
    }
}

/** "Agregar a…": elegir una lista o crear una nueva. */
@Composable
fun AddToPlaylistDialog(paths: List<String>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Playlists.load(ctx) }
    val media = paths.filter { FileTypes.kindOf(it) == FileKind.AUDIO || FileTypes.kindOf(it) == FileKind.VIDEO }
    var selected by remember { mutableStateOf(Playlists.lists.firstOrNull()?.id) }
    var creating by remember { mutableStateOf(Playlists.lists.isEmpty()) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agregar ${media.size} ${if (media.size == 1) "elemento" else "elementos"} a…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (media.isEmpty()) Text("Solo se pueden agregar canciones y videos.")
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(Playlists.lists, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { selected = p.id; creating = false }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = !creating && selected == p.id, onClick = { selected = p.id; creating = false })
                            Column(Modifier.weight(1f)) {
                                Text(p.name)
                                Text(p.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { creating = true }.padding(vertical = 8.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("Nueva lista", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                if (creating) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it }, singleLine = true,
                        placeholder = { Text("Nombre de la lista") }, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = media.isNotEmpty() && (creating || selected != null),
                onClick = {
                    if (creating) Playlists.create(ctx, newName, media)
                    else selected?.let { Playlists.add(ctx, it, media) }
                    onDismiss()
                }
            ) { Text("Agregar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Pestaña "Listas" de Música: listas guardadas y su contenido. */
@Composable
fun PlaylistsPane() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Playlists.load(ctx) }
    var openId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val open = openId?.let { Playlists.get(it) }

    if (open == null) {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { creating = true }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("Nueva lista", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            val all = Playlists.all()
            if (all.isEmpty()) item {
                Text(
                    "Aún no tienes listas. Crea una aquí, o en el explorador mantén presionado una canción o video y elige \"Agregar a lista\". " +
                        "Lo que marques con estrella aparece solo en \"Favoritos\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)
                )
            }
            items(all, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).clickable { openId = p.id }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val first = p.items.firstOrNull()
                    if (first != null) FileThumb(first, false, modifier = Modifier.padding(end = 12.dp).size(52.dp).clip(RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        Text(p.summary() + if (p.id == Playlists.FAVORITES) " · automática" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (p.audio.isNotEmpty()) IconButton(onClick = { Playlists.play(ctx, p, videos = false) }) {
                        Icon(Icons.Filled.PlayArrow, "Reproducir canciones")
                    }
                    if (p.videos.isNotEmpty()) IconButton(onClick = { Playlists.play(ctx, p, videos = true) }) {
                        Icon(Icons.Filled.OndemandVideo, "Reproducir videos")
                    }
                }
            }
        }
    } else {
        androidx.activity.compose.BackHandler { openId = null }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { openId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Listas") }
                Column(Modifier.weight(1f)) {
                    Text(open.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(open.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (open.id != Playlists.FAVORITES) {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, "Renombrar") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Borrar lista") }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (open.audio.isNotEmpty()) androidx.compose.material3.Button(onClick = { Playlists.play(ctx, open, videos = false) }) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Canciones")
                }
                if (open.videos.isNotEmpty()) androidx.compose.material3.OutlinedButton(onClick = { Playlists.play(ctx, open, videos = true) }) {
                    Icon(Icons.Filled.OndemandVideo, null); Spacer(Modifier.width(6.dp)); Text("Videos")
                }
            }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                items(open.items, key = { it }) { p ->
                    val exists = File(p).exists()
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = exists) { Playlists.play(ctx, open, videos = FileTypes.kindOf(p) == FileKind.VIDEO, start = p) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FileThumb(p, false, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(File(p).nameWithoutExtension, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(
                                if (!exists) "Ya no existe" else if (FileTypes.kindOf(p) == FileKind.VIDEO) "Video" else "Canción",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (open.id != Playlists.FAVORITES) IconButton(onClick = { Playlists.remove(ctx, open.id, p) }) {
                            Icon(Icons.Filled.RemoveCircleOutline, "Quitar de la lista")
                        }
                    }
                }
            }
        }
    }
    if (creating) NameDialog("Nueva lista", "", onDismiss = { creating = false }) { creating = false; openId = Playlists.create(ctx, it).id }
    if (renaming && open != null) NameDialog("Renombrar lista", open.name, onDismiss = { renaming = false }) { renaming = false; Playlists.rename(ctx, open.id, it) }
    if (confirmDelete && open != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("¿Borrar la lista \"${open.name}\"?") },
        text = { Text("Solo se borra la lista. Las canciones y videos siguen en tu teléfono.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; Playlists.delete(ctx, open.id); openId = null }) { Text("Borrar lista") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
    )
}
