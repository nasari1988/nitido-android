package com.limpio.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

enum class AudioState(val code: Char, val label: String) {
    HAS('A', "Con audio"),
    NONE('X', "Sin pista de audio"),
    SILENT('Z', "Audio en silencio"),
    ERROR('E', "No se pudo revisar");

    companion object {
        fun of(c: Char) = entries.firstOrNull { it.code == c }
    }
}

/** Reglas puras (se prueban en cada compilación). */
object AudioMath {
    /** Pico máximo (fracción de la escala completa) por debajo del cual se considera silencio (~ ‑48 dB). */
    const val SILENCE_PEAK = 0.004

    fun classify(hasTrack: Boolean, peaks: List<Double>): AudioState = when {
        !hasTrack -> AudioState.NONE
        peaks.isEmpty() -> AudioState.HAS // hay pista pero no se pudo escuchar: no se marca como muda
        peaks.max() < SILENCE_PEAK -> AudioState.SILENT
        else -> AudioState.HAS
    }

    fun peak16(samples: ShortArray, n: Int = samples.size): Double {
        var m = 0
        for (i in 0 until n) { val v = abs(samples[i].toInt()); if (v > m) m = v }
        return m / 32768.0
    }

    /** Dónde escuchar: inicio, medio y final (o solo el inicio si el video es muy corto). */
    fun positionsUs(durationUs: Long): List<Long> =
        if (durationUs > 6_000_000) listOf(0.1, 0.5, 0.9).map { (durationUs * it).toLong() } else listOf(0L)

    fun key(path: String, size: Long, modified: Long) = "$path|$size|$modified"
}

/** Revisa si un video tiene audio, y si lo que tiene es silencio. Recuerda los resultados. */
object AudioCheck {
    private val cache = ConcurrentHashMap<String, AudioState>()
    @Volatile private var loaded = false
    @Volatile private var dirty = false

    private fun file(ctx: Context) = File(ctx.filesDir, "audio_videos_v1.tsv")

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            file(ctx).takeIf { it.exists() }?.forEachLine { l ->
                val i = l.lastIndexOf('\t')
                if (i > 0) AudioState.of(l[i + 1])?.let { cache[l.substring(0, i)] = it }
            }
        } catch (_: Exception) {}
    }

    fun save(ctx: Context) {
        if (!dirty) return
        dirty = false
        try {
            file(ctx).bufferedWriter().use { w ->
                cache.forEach { (k, v) -> if (File(k.substringBefore('|')).exists()) w.write("$k\t${v.code}\n") }
            }
        } catch (_: Exception) {}
    }

    fun cached(path: String): AudioState? {
        val f = File(path)
        return cache[AudioMath.key(path, f.length(), f.lastModified())]
    }

    fun check(path: String): AudioState {
        val f = File(path)
        val key = AudioMath.key(path, f.length(), f.lastModified())
        cache[key]?.let { return it }
        val r = analyze(path)
        cache[key] = r
        dirty = true
        return r
    }

    /** Sin memoria (para la autoprueba). */
    fun analyze(path: String): AudioState {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            val idx = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return AudioState.NONE
            val fmt = ex.getTrackFormat(idx)
            ex.selectTrack(idx)
            val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val peaks = try {
                val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                try {
                    codec.configure(fmt, null, null, 0)
                    codec.start()
                    AudioMath.positionsUs(dur).map { pos ->
                        ex.seekTo(pos, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        codec.flush()
                        decodePeak(ex, codec, 2_000_000L)
                    }
                } finally {
                    try { codec.stop() } catch (_: Exception) {}
                    codec.release()
                }
            } catch (e: Exception) {
                emptyList() // formato de audio que el teléfono no sabe decodificar
            }
            return AudioMath.classify(true, peaks)
        } catch (e: Exception) {
            return AudioState.ERROR
        } finally {
            ex.release()
        }
    }

    /** Decodifica ~[spanUs] de audio desde la posición actual y devuelve el pico. */
    private fun decodePeak(ex: MediaExtractor, codec: MediaCodec, spanUs: Long): Double {
        val info = MediaCodec.BufferInfo()
        var peak = 0.0
        var start = -1L
        var inputDone = false
        var outputDone = false
        var float = false
        var guard = 0
        while (!outputDone && guard++ < 4000) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = ex.readSampleData(buf, 0)
                    val t = ex.sampleTime
                    if (start < 0 && t >= 0) start = t
                    if (n < 0 || (start >= 0 && t - start > spanUs)) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, t, 0)
                        ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5_000)
            when {
                o >= 0 -> {
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(o)!!.duplicate().order(ByteOrder.nativeOrder())
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        peak = maxOf(peak, if (float) peakFloat(ob) else peakShort(ob))
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    float = Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
            }
        }
        return peak
    }

    private fun peakShort(b: ByteBuffer): Double {
        val s = b.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
        var m = 0
        while (s.hasRemaining()) { val v = abs(s.get().toInt()); if (v > m) m = v }
        return m / 32768.0
    }

    private fun peakFloat(b: ByteBuffer): Double {
        val s = b.slice().order(ByteOrder.nativeOrder()).asFloatBuffer()
        var m = 0f
        while (s.hasRemaining()) { val v = abs(s.get()); if (v > m) m = v }
        return m.toDouble()
    }
}

/** Revisión en segundo plano de los videos que se están viendo. La UI observa [states]. */
object AudioScan {
    val states = mutableStateMapOf<String, AudioState>()
    var running by mutableStateOf(false)
        private set
    var done by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun start(ctx: Context, videos: List<String>) {
        val app = ctx.applicationContext
        job?.cancel()
        job = scope.launch {
            withContext(Dispatchers.IO) { AudioCheck.load(app) }
            // Lo que ya se conoce aparece al instante.
            val known = withContext(Dispatchers.IO) { videos.mapNotNull { p -> AudioCheck.cached(p)?.let { p to it } } }
            known.forEach { (p, s) -> states[p] = s }
            val todo = videos.filter { it !in states }
            if (todo.isEmpty()) { running = false; return@launch }
            running = true
            done = 0
            total = todo.size
            val io = Dispatchers.IO.limitedParallelism(2)
            todo.chunked(8).forEach { chunk ->
                val res = chunk.map { p -> async(io) { p to AudioCheck.check(p) } }.awaitAll()
                res.forEach { (p, s) -> states[p] = s }
                done += res.size
            }
            withContext(Dispatchers.IO) { AudioCheck.save(app) }
            running = false
        }
    }

    fun forget(paths: Collection<String>) = paths.forEach { states.remove(it) }
}

/** Orden de reproducción (se prueba en cada compilación). */
object PlayOrder {
    fun next(index: Int, size: Int, repeat: Boolean): Int? = when {
        index + 1 < size -> index + 1
        repeat && size > 0 -> 0
        else -> null
    }

    /** El actual queda primero y el resto se mezcla. */
    fun shuffled(list: List<String>, current: Int, seed: Long = System.nanoTime()): List<String> {
        if (list.isEmpty()) return list
        val cur = list[current.coerceIn(0, list.lastIndex)]
        return listOf(cur) + list.filterIndexed { i, _ -> i != current }.shuffled(kotlin.random.Random(seed))
    }

    /** Mueve un elemento de [from] a [to] (para reordenar arrastrando). */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList()
        val x = m.removeAt(from)
        m.add(to, x)
        return m
    }
}
