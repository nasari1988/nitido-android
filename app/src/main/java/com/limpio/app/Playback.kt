package com.limpio.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Servicio de música: sigue sonando con la pantalla apagada o fuera de la app,
 * con controles en la notificación y en la pantalla de bloqueo.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true) // pausa al desconectar audífonos
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/** Preferencias de reproducción. */
object PlayPrefs {
    var videoAutoplay by mutableStateOf(true)
    /** Segundos de espera entre videos (0 = sin espera). */
    var videoWait by mutableStateOf(0)
    var musicAutoNext by mutableStateOf(true)
    private var ctx: Context? = null

    fun load(c: Context) {
        ctx = c.applicationContext
        val p = c.getSharedPreferences("reproduccion", Context.MODE_PRIVATE)
        videoAutoplay = p.getBoolean("videoAutoplay", true)
        videoWait = p.getInt("videoWait", 0)
        musicAutoNext = p.getBoolean("musicAutoNext", true)
    }

    fun save() {
        ctx?.getSharedPreferences("reproduccion", Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("videoAutoplay", videoAutoplay)?.putBoolean("musicAutoNext", musicAutoNext)?.putInt("videoWait", videoWait)?.apply()
    }
}

/** Estado y control de la música para toda la app (la UI lo observa). */
object Music {
    private var future: ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
        private set
    private val scope = MainScope()
    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var pending: (() -> Unit)? = null

    var hasMedia by mutableStateOf(false)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set
    var artist by mutableStateOf("")
        private set
    var currentPath by mutableStateOf<String?>(null)
        private set
    var position by mutableStateOf(0L)
        private set
    var duration by mutableStateOf(0L)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    var speed by mutableStateOf(1f)
        private set
    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var sleepAt by mutableStateOf<Long?>(null)
        private set
    var showNowPlaying by mutableStateOf(false)
    /** De dónde viene la cola: "Carpeta · Música" o "Lista · Para el camino". */
    var source by mutableStateOf("")
    /** Cambia cada vez que la cola cambia (para redibujar "A continuación"). */
    var queueVersion by mutableStateOf(0)

    fun connect(ctx: Context) {
        if (future != null) return
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx.applicationContext, token).buildAsync()
        future = f
        f.addListener({
            val c = try { f.get() } catch (e: Exception) { future = null; return@addListener }
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(Player.EVENT_TIMELINE_CHANGED)) queueVersion++
                    sync()
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // "Al terminar una, seguir con la siguiente" apagado: se detiene al cambiar sola.
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !PlayPrefs.musicAutoNext) c.pause()
                }
            })
            sync()
            pending?.invoke()
            pending = null
            ticker?.cancel()
            ticker = scope.launch {
                while (true) {
                    controller?.let { position = it.currentPosition; duration = it.duration.coerceAtLeast(0) }
                    delay(500)
                }
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    /** Espera a que el servicio responda (para la autoprueba). */
    suspend fun awaitConnected(ctx: Context, timeoutMs: Long = 6000): Boolean {
        connect(ctx)
        return withTimeoutOrNull(timeoutMs) {
            while (controller == null) delay(100)
            true
        } ?: false
    }

    private fun sync() {
        val c = controller ?: return
        hasMedia = c.mediaItemCount > 0
        isPlaying = c.isPlaying
        val item = c.currentMediaItem
        currentPath = item?.mediaId
        val md = c.mediaMetadata
        val track = queue.firstOrNull { it.path == item?.mediaId }
        title = md.title?.toString()?.takeIf { it.isNotBlank() }
            ?: track?.title ?: item?.mediaId?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
        artist = md.artist?.toString()?.takeIf { it.isNotBlank() } ?: track?.artist ?: ""
        shuffle = c.shuffleModeEnabled
        repeat = c.repeatMode
        speed = c.playbackParameters.speed
        duration = c.duration.coerceAtLeast(0)
        position = c.currentPosition
    }

    private fun item(t: Track): MediaItem = MediaItem.Builder()
        .setMediaId(t.path)
        .setUri(Uri.fromFile(File(t.path)))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist.takeIf { it.isNotBlank() })
                .setAlbumTitle(t.album.takeIf { it.isNotBlank() })
                .build()
        )
        .build()

    fun play(ctx: Context, tracks: List<Track>, start: Int = 0, shuffled: Boolean = false, label: String? = null) {
        if (tracks.isEmpty()) return
        source = label ?: ("Carpeta · " + tracks[start.coerceIn(0, tracks.lastIndex)].path.substringBeforeLast('/').substringAfterLast('/'))
        val action = {
            controller?.run {
                queue = tracks
                setMediaItems(tracks.map { item(it) }, start.coerceIn(0, tracks.lastIndex), 0)
                shuffleModeEnabled = shuffled
                prepare()
                play()
            }
            Unit
        }
        if (controller != null) action() else { pending = action; connect(ctx) }
    }

    /** Reproducir archivos sueltos del explorador (los datos se leen del propio archivo). */
    fun playFiles(ctx: Context, paths: List<String>, start: Int, label: String? = null) {
        play(ctx, paths.map { Track(it, it.substringAfterLast('/').substringBeforeLast('.'), "", "", 0) }, start, label = label)
    }

    /** Toda la carpeta de la canción actual, en orden por nombre. */
    fun playFolder(ctx: Context, folder: String, startPath: String?) {
        val songs = File(folder).listFiles().orEmpty()
            .filter { it.isFile && FileTypes.kindOf(it.name) == FileKind.AUDIO }
            .sortedBy { it.name.lowercase() }.map { it.absolutePath }
        if (songs.isEmpty()) return
        val keepPos = if (startPath != null && startPath == currentPath) controller?.currentPosition ?: 0 else 0
        playFiles(ctx, songs, songs.indexOf(startPath).coerceAtLeast(0))
        if (keepPos > 0) controller?.seekTo(keepPos)
    }

    /** Lo que sigue, en el orden en que va a sonar (respeta aleatorio). Par = (índice en la cola, canción). */
    fun upcoming(): List<Pair<Int, Track>> {
        val c = controller ?: return emptyList()
        val tl = c.currentTimeline
        if (tl.isEmpty) return emptyList()
        val out = ArrayList<Pair<Int, Track>>()
        var i = c.currentMediaItemIndex
        var guard = 0
        while (i != C.INDEX_UNSET && guard++ < 1000) {
            val id = c.getMediaItemAt(i).mediaId
            out += i to (queue.firstOrNull { it.path == id } ?: Track(id, id.substringAfterLast('/').substringBeforeLast('.'), "", "", 0))
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        }
        return out
    }

    /** Reordenar la cola arrastrando. */
    fun move(from: Int, to: Int) {
        controller?.moveMediaItem(from, to)
        queue = PlayOrder.move(queue, from, to)
        queueVersion++
    }

    fun toggle() = controller?.run { if (isPlaying) pause() else { if (playbackState == Player.STATE_ENDED) seekTo(0); play() } }
    fun pause() { controller?.pause() }
    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.run { if (currentPosition > 3000) seekTo(0) else seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun toggleShuffle() = controller?.run { shuffleModeEnabled = !shuffleModeEnabled }
    fun cycleRepeat() = controller?.run {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun setSpeed(s: Float) = controller?.setPlaybackSpeed(s)
    fun playAt(index: Int) = controller?.run { seekTo(index, 0); play() }

    fun stop() {
        controller?.run { stop(); clearMediaItems() }
        showNowPlaying = false
    }

    /** Temporizador: pausa la música después de [minutes] minutos (0 = apagar). */
    fun sleepAfter(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) { sleepAt = null; return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        sleepAt = at
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            sleepAt = null
        }
    }
}

/** Petición de abrir el reproductor de video (lista de archivos y cuál empezar). */
data class VideoRequest(val paths: List<String>, val index: Int, val source: String = "")

object Playback {
    var video by mutableStateOf<VideoRequest?>(null)

    fun playVideo(paths: List<String>, start: Int, source: String? = null) {
        if (paths.isEmpty()) return
        Music.pause()
        val i = start.coerceIn(0, paths.lastIndex)
        video = VideoRequest(paths, i, source ?: ("Carpeta: " + File(paths[i]).parentFile?.name.orEmpty()))
    }
}

/** Recuerda dónde te quedaste en cada video. */
object VideoPositions {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("posiciones_video", Context.MODE_PRIVATE)

    fun get(ctx: Context, path: String): Long = prefs(ctx).getLong(path, 0L)

    fun save(ctx: Context, path: String, pos: Long, dur: Long) {
        val e = prefs(ctx).edit()
        if (pos > 5_000 && dur > 0 && pos < dur - 5_000) e.putLong(path, pos) else e.remove(path)
        e.apply()
    }
}
