package com.limpio.app

import android.app.Application
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class MusicTab(val label: String) { SONGS("Canciones"), ALBUMS("Álbumes"), ARTISTS("Artistas"), FOLDERS("Carpetas") }

class MusicViewModel(app: Application) : AndroidViewModel(app) {
    var tracks by mutableStateOf<List<Track>?>(null)
        private set
    var tab by mutableStateOf(MusicTab.SONGS)
    var openGroup by mutableStateOf<TrackGroup?>(null)

    fun load(force: Boolean = false) {
        if (tracks != null && !force) return
        viewModelScope.launch {
            tracks = withContext(Dispatchers.IO) { queryTracks() }
        }
    }

    @Suppress("DEPRECATION")
    private fun queryTracks(): List<Track> {
        val out = ArrayList<Track>()
        val cols = arrayOf(
            MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED,
        )
        try {
            getApplication<Application>().contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    if (Protection.isHiddenSystem(path) || !File(path).exists()) continue
                    out += Track(
                        path = path,
                        title = MediaUtil.unknown(c.getString(1), File(path).nameWithoutExtension),
                        artist = MediaUtil.unknown(c.getString(2), "Artista desconocido"),
                        album = MediaUtil.unknown(c.getString(3), "Álbum desconocido"),
                        durationMs = c.getLong(4),
                        dateAdded = c.getLong(5),
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out.sortedBy { it.title.lowercase() }
    }
}

/** Carátula grande, leída de la propia canción. */
@Composable
fun rememberArtwork(path: String?, sizePx: Int = 640): ImageBitmap? {
    val art by produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(path)
                mmr.embeddedPicture?.let { bytes ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var s = 1
                    while (minOf(o.outWidth, o.outHeight) / (s * 2) >= sizePx) s *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
                }
            } catch (e: Exception) {
                null
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        }
    }
    return art
}

@Composable
private fun Artwork(path: String?, modifier: Modifier, big: Boolean = false) {
    val art = rememberArtwork(path, if (big) 640 else 160)
    Box(modifier.clip(RoundedCornerShape(if (big) 28.dp else 10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(if (big) 96.dp else 22.dp))
    }
}

/** Biblioteca de música: canciones, álbumes, artistas y carpetas. */
@Composable
fun MusicLibraryScreen(vm: MusicViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.load() }
    val group = vm.openGroup
    BackHandler { if (group != null) vm.openGroup = null else onBack() }
    val all = vm.tracks

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (group != null) vm.openGroup = null else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
            }
            Column(Modifier.weight(1f)) {
                Text(group?.name ?: "Música", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (group != null) Text(group.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (all == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        if (all.isEmpty()) {
            Text(
                "No encontré música en tu teléfono. Los audios sueltos (notas de voz, etc.) los puedes abrir desde el explorador.",
                modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        if (group == null) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MusicTab.entries.forEach { t ->
                    FilterChip(selected = vm.tab == t, onClick = { vm.tab = t }, label = { Text(t.label) })
                }
            }
        }
        val songs = group?.tracks ?: all
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { Music.play(ctx, songs, (songs.indices).random(), shuffled = true); Music.showNowPlaying = true }) {
                Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Aleatorio")
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "${songs.size} canciones · ${MediaUtil.fmtTime(songs.sumOf { it.durationMs })}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val groups = when {
            group != null -> null
            vm.tab == MusicTab.ALBUMS -> MediaUtil.albums(all)
            vm.tab == MusicTab.ARTISTS -> MediaUtil.artists(all)
            vm.tab == MusicTab.FOLDERS -> MediaUtil.folders(all)
            else -> null
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (groups != null) {
                itemsIndexed(groups, key = { _, g -> g.name + g.subtitle }) { _, g ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { vm.openGroup = g }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Artwork(g.tracks.first().path, Modifier.size(52.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(g.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                itemsIndexed(songs, key = { _, t -> t.path }) { i, t ->
                    val playing = Music.currentPath == t.path
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (playing) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { Music.play(ctx, songs, i) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Artwork(t.path, Modifier.size(48.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (playing) Icon(Icons.Filled.GraphicEq, "Sonando", tint = MaterialTheme.colorScheme.primary)
                        else Text(MediaUtil.fmtTime(t.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Mini reproductor de vidrio que flota encima de las pestañas. */
@Composable
fun MiniPlayer() {
    if (!Music.hasMedia || Music.showNowPlaying) return
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 6.dp)) {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).clickable { Music.showNowPlaying = true }) {
            Row(Modifier.padding(start = 8.dp, end = 2.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(Music.currentPath, Modifier.size(42.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(Music.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (Music.artist.isNotBlank()) Text(
                        Music.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { Music.toggle() }) {
                    Icon(if (Music.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (Music.isPlaying) "Pausar" else "Reproducir")
                }
                IconButton(onClick = { Music.next() }) { Icon(Icons.Filled.SkipNext, "Siguiente") }
                IconButton(onClick = { Music.stop() }) { Icon(Icons.Filled.Close, "Cerrar reproductor") }
            }
            val f = if (Music.duration > 0) Music.position.toFloat() / Music.duration else 0f
            Box(Modifier.fillMaxWidth().height(3.dp)) {
                Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(3.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

/** Pantalla "Reproduciendo". */
@Composable
fun NowPlayingScreen() {
    val ctx = LocalContext.current
    BackHandler { Music.showNowPlaying = false }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val dur = Music.duration.coerceAtLeast(1)

    Box(Modifier.fillMaxSize()) {
        GlassBackground()
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Music.showNowPlaying = false }) { Icon(Icons.Filled.KeyboardArrowDown, "Minimizar") }
                Text(
                    "Reproduciendo", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { Music.currentPath?.let { FileOps.share(ctx, listOf(it)) } }) { Icon(Icons.Filled.Share, "Compartir") }
            }
            Spacer(Modifier.height(12.dp))
            Artwork(Music.currentPath, Modifier.fillMaxWidth().aspectRatio(1f), big = true)
            Spacer(Modifier.height(24.dp))
            Text(Music.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Text(Music.artist.ifBlank { " " }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Slider(
                value = seeking ?: (Music.position.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { seeking = it },
                onValueChangeFinished = { seeking?.let { Music.seekTo((it * dur).toLong()) }; seeking = null },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(MediaUtil.fmtTime(((seeking ?: -1f) * dur).toLong().takeIf { seeking != null } ?: Music.position), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(MediaUtil.fmtTime(Music.duration), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Music.toggleShuffle() }) {
                    Icon(Icons.Filled.Shuffle, "Aleatorio", tint = if (Music.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { Music.previous() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipPrevious, "Anterior", Modifier.size(36.dp)) }
                Box(
                    Modifier.size(76.dp).clip(RoundedCornerShape(38.dp)).background(MaterialTheme.colorScheme.primary).clickable { Music.toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (Music.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (Music.isPlaying) "Pausar" else "Reproducir",
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = { Music.next() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipNext, "Siguiente", Modifier.size(36.dp)) }
                IconButton(onClick = { Music.cycleRepeat() }) {
                    Icon(
                        if (Music.repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repetir",
                        tint = if (Music.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().glass().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                NpAction(Icons.AutoMirrored.Filled.QueueMusic, "Cola") { showQueue = true }
                Box {
                    NpAction(Icons.Filled.Speed, if (Music.speed == 1f) "Velocidad" else "${Music.speed}×") { speedMenu = true }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                            DropdownMenuItem(text = { Text("${s}×") }, onClick = { Music.setSpeed(s); speedMenu = false })
                        }
                    }
                }
                Box {
                    val left = Music.sleepAt?.let { ((it - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1 }
                    NpAction(Icons.Filled.Bedtime, if (left != null) "$left min" else "Temporizador") { sleepMenu = true }
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                        listOf(15, 30, 45, 60, 90).forEach { m ->
                            DropdownMenuItem(text = { Text("En $m minutos") }, onClick = { Music.sleepAfter(m); sleepMenu = false })
                        }
                        if (Music.sleepAt != null) DropdownMenuItem(text = { Text("Apagar temporizador") }, onClick = { Music.sleepAfter(0); sleepMenu = false })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (showQueue) {
        AlertDialog(
            onDismissRequest = { showQueue = false },
            title = { Text("Cola de reproducción") },
            text = {
                LazyColumn(Modifier.height(380.dp)) {
                    itemsIndexed(Music.queue, key = { i, t -> "$i${t.path}" }) { i, t ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { Music.playAt(i); showQueue = false }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                t.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (t.path == Music.currentPath) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (t.path == Music.currentPath) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQueue = false }) { Text("Cerrar") } }
        )
    }
}

@Composable
private fun NpAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
