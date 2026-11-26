package com.limpio.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Switch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Arma el MediaItem con los subtítulos .srt/.vtt que estén junto al video. */
fun videoItem(path: String): MediaItem {
    val b = MediaItem.Builder().setUri(Uri.fromFile(File(path))).setMediaId(path)
    MediaUtil.findSubtitle(File(path))?.let { sub ->
        val mime = when (sub.extension.lowercase()) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        b.setSubtitleConfigurations(
            listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sub))
                    .setMimeType(mime).setLanguage("es").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            )
        )
    }
    return b.build()
}

private val RESIZE_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Ajustar",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Llenar pantalla",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Estirar",
)

/** Reproductor de video a pantalla completa con gestos. */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(req: VideoRequest, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val view = LocalView.current
    val player = remember { ExoPlayer.Builder(ctx).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build() }
    // La cola es editable: origen Carpeta/Lista, aleatorio y repetir.
    var baseList by remember { mutableStateOf(req.paths) }
    var list by remember { mutableStateOf(req.paths) }
    var source by remember { mutableStateOf(req.source) }
    var index by remember { mutableIntStateOf(req.index) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var addToList by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { Playlists.load(ctx) }
    val path = list.getOrNull(index) ?: return

    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var resize by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var speedMenu by remember { mutableStateOf(false) }
    var hasSubs by remember { mutableStateOf(false) }
    var subsOn by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintAt by remember { mutableLongStateOf(0L) }
    var resumedFrom by remember { mutableLongStateOf(0L) }
    var inPip by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf<Boolean?>(null) }

    fun flash(text: String) { hint = text; hintAt = System.currentTimeMillis() }
    // Siempre el video que suena ahora (no el de cuando se creó la función).
    fun saveNow() {
        val cur = list.getOrNull(player.currentMediaItemIndex) ?: return
        VideoPositions.save(ctx, cur, player.currentPosition, player.duration)
    }
    fun goTo(i: Int) {
        if (i !in list.indices || i >= player.mediaItemCount) return
        countdown = null
        if (i != player.currentMediaItemIndex) {
            saveNow()
            val saved = VideoPositions.get(ctx, list[i])
            player.seekTo(i, saved)
            resumedFrom = saved
        } else player.seekTo(0)
        player.play()
    }
    fun setQueue(paths: List<String>, label: String) {
        if (paths.isEmpty()) return
        val cur = path
        baseList = paths
        list = if (shuffle) PlayOrder.shuffled(paths, paths.indexOf(cur).coerceAtLeast(0)) else paths
        source = label
        val i = list.indexOf(cur)
        if (i >= 0) index = i else { saveNow(); index = 0 }
        countdown = null
    }
    fun folderVideos(): List<String> = File(path).parentFile?.listFiles().orEmpty()
        .filter { it.isFile && FileTypes.kindOf(it.name) == FileKind.VIDEO }
        .sortedBy { it.name.lowercase() }.map { it.absolutePath }
    val nextIndex = PlayOrder.next(index, list.size, repeat)
    // Al terminar uno: cierra si no hay más; si hay espera o el automático está apagado, el
    // reproductor se detiene al final y aquí se decide (cuenta atrás o mostrar controles).
    val onItemEnd = androidx.compose.runtime.rememberUpdatedState {
        list.getOrNull(player.currentMediaItemIndex)?.let { VideoPositions.save(ctx, it, 0, 0) }
        when {
            nextIndex == null -> onClose()
            PlayPrefs.videoAutoplay && PlayPrefs.videoWait > 0 -> countdown = PlayPrefs.videoWait
            PlayPrefs.videoAutoplay -> { player.seekToNextMediaItem(); player.play() }
            else -> controls = true
        }
    }

    // Toda la cola va al reproductor: así precarga el siguiente y pasa sin corte.
    LaunchedEffect(list) {
        val cur = list.getOrNull(index) ?: return@LaunchedEffect
        val samePlaying = player.currentMediaItem?.mediaId == cur && player.mediaItemCount > 0
        val pos = if (samePlaying) player.currentPosition else VideoPositions.get(ctx, cur)
        val items = withContext(Dispatchers.IO) { list.map { videoItem(it) } }
        player.setMediaItems(items, index, pos)
        player.prepare()
        if (!samePlaying) resumedFrom = pos
        player.playWhenReady = true
    }
    // Pausa al final de cada video solo cuando hace falta esperar o preguntar.
    LaunchedEffect(PlayPrefs.videoAutoplay, PlayPrefs.videoWait, repeat) {
        player.pauseAtEndOfMediaItems = !PlayPrefs.videoAutoplay || PlayPrefs.videoWait > 0
        player.repeatMode = if (repeat) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                playing = p.isPlaying
                hasSubs = p.currentTracks.containsType(C.TRACK_TYPE_TEXT)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onItemEnd.value()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) onItemEnd.value()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // El índice de la pantalla sigue al reproductor (siguiente automático, gestos, etc.).
                val i = player.currentMediaItemIndex
                if (i in list.indices && i != index) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        list.getOrNull(index)?.let { VideoPositions.save(ctx, it, 0, 0) }
                        resumedFrom = 0
                    }
                    index = i
                    countdown = null
                }
            }
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                // Gira solo la pantalla para videos horizontales (la primera vez).
                if (landscape == null && size.width > 0) {
                    val wide = size.width * size.pixelWidthHeightRatio > size.height
                    landscape = wide
                    activity?.requestedOrientation =
                        if (wide) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
        player.addListener(listener)
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        view.keepScreenOn = true
        val pipListener = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        (activity as? ComponentActivity)?.addOnPictureInPictureModeChangedListener(pipListener)
        onDispose {
            saveNow()
            player.removeListener(listener)
            player.release()
            (activity as? ComponentActivity)?.removeOnPictureInPictureModeChangedListener(pipListener)
            insets?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = -1f } }
        }
    }

    // Reloj: posición, guardado periódico y ocultar controles solos.
    LaunchedEffect(Unit) {
        var lastSave = 0L
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            val now = System.currentTimeMillis()
            if (now - lastSave > 5000) { saveNow(); lastSave = now }
            if (hint != null && now - hintAt > 900) hint = null
            delay(250)
        }
    }
    LaunchedEffect(controls, playing, position / 3000) {
        if (controls && playing && !locked) { delay(3500); controls = false }
    }
    LaunchedEffect(countdown) {
        val c = countdown ?: return@LaunchedEffect
        if (c > 0) { delay(1000); if (countdown == c) countdown = c - 1 }
        else { countdown = null; player.seekToNextMediaItem(); player.play() }
    }
    LaunchedEffect(resumedFrom) { if (resumedFrom > 0) { delay(5000); resumedFrom = 0 } }

    BackHandler {
        when {
            locked -> flash("Pantalla bloqueada: toca el candado")
            showQueue -> showQueue = false
            else -> onClose()
        }
    }

    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var brightness by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 }
                ?: (Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
        )
    }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVol.toFloat()) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    this.player = player
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.resizeMode = RESIZE_MODES[resize].first },
            modifier = Modifier.fillMaxSize()
        )

        // Gestos: toque = controles; doble toque = ±10 s; deslizar = brillo (izq.) / volumen (der.).
        Box(
            Modifier.fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { if (locked) flash("Pantalla bloqueada") else controls = !controls },
                        onDoubleTap = { o ->
                            if (!locked) {
                                if (o.x < size.width / 2) { player.seekBack(); flash("−10 s") } else { player.seekForward(); flash("+10 s") }
                            }
                        }
                    )
                }
                .pointerInput(locked) {
                    if (!locked) {
                        var leftSide = true
                        detectVerticalDragGestures(
                            onDragStart = { o -> leftSide = o.x < size.width / 2 },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                val delta = -dy / (size.height * 0.8f)
                                if (leftSide) {
                                    brightness = (brightness + delta).coerceIn(0.02f, 1f)
                                    activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = brightness } }
                                    flash("Brillo ${(brightness * 100).toInt()}%")
                                } else {
                                    volume = (volume + delta).coerceIn(0f, 1f)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVol).toInt(), 0)
                                    flash("Volumen ${(volume * 100).toInt()}%")
                                }
                            }
                        )
                    }
                }
        )

        hint?.let {
            Text(
                it, color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp))
                    .background(Color(0x99000000)).padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }

        countdown?.let { c ->
            val n = nextIndex?.let { list.getOrNull(it) }
            if (n != null && !inPip) {
                Row(
                    Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp).widthIn(max = 340.dp)
                        .clip(RoundedCornerShape(18.dp)).background(Color(0xCC1C1C1E)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FileThumb(n, false, modifier = Modifier.size(width = 84.dp, height = 52.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Siguiente en $c s", color = Color(0xFF8CF0C8), style = MaterialTheme.typography.labelMedium)
                        Text(File(n).name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { countdown = null; controls = true }) { Text("Cancelar", color = Color.White) }
                            TextButton(onClick = { countdown = 0 }) { Text("Reproducir ya", color = Color(0xFF8CF0C8)) }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            showQueue && !inPip && !locked, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Column(
                Modifier.fillMaxHeight().widthIn(max = 330.dp).fillMaxWidth(0.85f).safeDrawingPadding().padding(8.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Color(0xE61C1C1E)).padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("A continuación", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(source, color = Color(0xAAFFFFFF), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { showQueue = false }) { Icon(Icons.Filled.Close, "Cerrar", tint = Color.White) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("Reproducir el siguiente al terminar", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = PlayPrefs.videoAutoplay, onCheckedChange = { PlayPrefs.videoAutoplay = it; PlayPrefs.save() })
                }
                if (PlayPrefs.videoAutoplay) {
                    Text("Espera entre videos", color = Color(0xAAFFFFFF), style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        listOf(0 to "Sin espera", 3 to "3 s", 5 to "5 s", 10 to "10 s").forEach { (s, l) ->
                            val on = PlayPrefs.videoWait == s
                            Text(
                                l, style = MaterialTheme.typography.labelMedium,
                                color = if (on) Color.Black else Color.White,
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(if (on) Color(0xFF8CF0C8) else Color(0x33FFFFFF))
                                    .clickable { PlayPrefs.videoWait = s; PlayPrefs.save() }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(list, key = { i, p -> "$i|$p" }) { i, p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (i == index) Color(0x338CF0C8) else Color.Transparent)
                                .clickable { goTo(i) }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                FileThumb(p, false, modifier = Modifier.size(width = 72.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)))
                                AudioBadge(AudioScan.states[p] ?: AudioCheck.cached(p), Modifier.align(Alignment.BottomEnd).padding(2.dp), 14.dp)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                (if (i == index) "▶ " else "") + File(p).nameWithoutExtension, color = Color.White, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                TextButton(onClick = { addToList = listOf(path) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = Color(0xFF8CF0C8))
                    Spacer(Modifier.width(6.dp))
                    Text("Agregar a lista", color = Color(0xFF8CF0C8))
                }
            }
        }
        addToList?.let { p -> AddToPlaylistDialog(p) { addToList = null } }

        if (locked && !inPip) {
            IconButton(
                onClick = { locked = false; controls = true },
                modifier = Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(16.dp)
                    .clip(CircleShape).background(Color(0x66000000))
            ) { Icon(Icons.Filled.Lock, "Desbloquear", tint = Color.White) }
        }

        AnimatedVisibility(controls && !locked && !inPip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp)) {
                // Barra superior
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).clip(RoundedCornerShape(18.dp))
                        .background(Color(0x73000000)).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cerrar", tint = Color.White) }
                    Text(
                        File(path).name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold
                    )
                    Box {
                        TextButton(onClick = { sourceMenu = true }) {
                            Text(source.ifEmpty { "Carpeta" } + " ▾", color = Color(0xFF8CF0C8), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(max = 150.dp))
                        }
                        DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                            val folder = File(path).parentFile?.name.orEmpty()
                            DropdownMenuItem(
                                text = { Text("Carpeta: $folder") },
                                leadingIcon = { Icon(Icons.Filled.Folder, null) },
                                onClick = { sourceMenu = false; setQueue(folderVideos(), "Carpeta: $folder") }
                            )
                            Playlists.all().filter { it.videos.isNotEmpty() }.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text("Lista: ${p.name} (${p.videos.size})") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                                    onClick = { sourceMenu = false; setQueue(p.videos.filter { File(it).exists() }, "Lista: ${p.name}") }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Agregar este video a una lista…") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                                onClick = { sourceMenu = false; addToList = listOf(path) }
                            )
                        }
                    }
                    IconButton(onClick = { showQueue = !showQueue }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "A continuación", tint = Color.White)
                    }
                    if (hasSubs) {
                        IconButton(onClick = {
                            subsOn = !subsOn
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn).build()
                            flash(if (subsOn) "Subtítulos activados" else "Subtítulos apagados")
                        }) {
                            Icon(if (subsOn) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled, "Subtítulos", tint = Color.White)
                        }
                    }
                    Box {
                        TextButton(onClick = { speedMenu = true }) { Text("${speed}×", color = Color.White) }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                                DropdownMenuItem(text = { Text("${s}×") }, onClick = {
                                    speed = s; player.setPlaybackSpeed(s); speedMenu = false
                                })
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        IconButton(onClick = {
                            val vs = player.videoSize
                            val r = if (vs.width > 0 && vs.height > 0) Rational(vs.width, vs.height) else Rational(16, 9)
                            val safe = when {
                                r.toFloat() > 2.39f -> Rational(239, 100)
                                r.toFloat() < 0.42f -> Rational(100, 239)
                                else -> r
                            }
                            activity?.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(safe).build())
                        }) { Icon(Icons.Filled.PictureInPictureAlt, "Ventana flotante", tint = Color.White) }
                    }
                }

                // Centro
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (list.size > 1) RoundBtn(Icons.Filled.SkipPrevious, "Anterior", 52) {
                        if (player.currentPosition > 3000 || (index == 0 && !repeat)) player.seekTo(0) else goTo(if (index > 0) index - 1 else list.lastIndex)
                    }
                    RoundBtn(Icons.Filled.Replay10, "Retroceder 10 segundos", 56) { player.seekBack() }
                    RoundBtn(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pausar" else "Reproducir", 72) {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                    RoundBtn(Icons.Filled.Forward10, "Adelantar 10 segundos", 56) { player.seekForward() }
                    if (list.size > 1) RoundBtn(Icons.Filled.SkipNext, "Siguiente", 52) { nextIndex?.let { goTo(it) } }
                }

                // Barra inferior
                Column(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter).clip(RoundedCornerShape(18.dp))
                        .background(Color(0x73000000)).padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    if (resumedFrom > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Seguiste desde ${MediaUtil.fmtTime(resumedFrom)}", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { player.seekTo(0); resumedFrom = 0 }) { Text("Desde el inicio", color = Color(0xFF8CF0C8)) }
                        }
                    }
                    val dur = duration.coerceAtLeast(1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(MediaUtil.fmtTime(seeking?.let { (it * dur).toLong() } ?: position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = seeking ?: (position.toFloat() / dur).coerceIn(0f, 1f),
                            onValueChange = { seeking = it; controls = true },
                            onValueChangeFinished = { seeking?.let { player.seekTo((it * dur).toLong()) }; seeking = null },
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF8CF0C8), inactiveTrackColor = Color(0x55FFFFFF)),
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                        )
                        Text(MediaUtil.fmtTime(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { locked = true; controls = false; flash("Pantalla bloqueada") }) {
                            Icon(Icons.Filled.LockOpen, "Bloquear controles", tint = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            shuffle = !shuffle
                            val cur = path
                            list = if (shuffle) PlayOrder.shuffled(baseList, baseList.indexOf(cur).coerceAtLeast(0)) else baseList
                            index = list.indexOf(cur).coerceAtLeast(0)
                            flash(if (shuffle) "Aleatorio" else "En orden")
                        }) { Icon(Icons.Filled.Shuffle, "Aleatorio", tint = if (shuffle) Color(0xFF8CF0C8) else Color.White) }
                        if (list.size > 1) Text("${index + 1} de ${list.size}", color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = { repeat = !repeat; flash(if (repeat) "Repetir la cola" else "Sin repetir") }) {
                            Icon(Icons.Filled.Repeat, "Repetir", tint = if (repeat) Color(0xFF8CF0C8) else Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { resize = (resize + 1) % RESIZE_MODES.size; flash(RESIZE_MODES[resize].second) }) {
                            Icon(Icons.Filled.AspectRatio, "Ajuste de pantalla", tint = Color.White)
                        }
                        IconButton(onClick = {
                            val nowLandscape = landscape != false
                            landscape = !nowLandscape
                            activity?.requestedOrientation =
                                if (nowLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }) { Icon(Icons.Filled.ScreenRotation, "Girar", tint = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: Int, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size.dp).clip(CircleShape).background(Color(0x66000000))
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp)) }
}
