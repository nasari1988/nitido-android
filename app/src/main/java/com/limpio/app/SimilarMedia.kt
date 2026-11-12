package com.limpio.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Avance de una tarea larga, para la barra de progreso. */
class Progress {
    @Volatile var stage: String = ""
        private set
    val done = AtomicInteger(0)
    val total = AtomicInteger(0)

    fun start(name: String, total: Int = 0) {
        stage = name
        done.set(0)
        this.total.set(total)
    }

    fun snapshot(count: Int = 0) = Phase.Scanning(count, stage, done.get(), total.get())
}

/** Reduce una imagen a 9x8 en grises y calcula su huella visual (dHash). */
fun bitmapDHash(bmp: Bitmap): Long? {
    val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
    val px = IntArray(72)
    small.getPixels(px, 0, 9, 0, 0, 9, 8)
    if (small !== bmp) small.recycle()
    val gray = IntArray(72) { i ->
        val c = px[i]
        (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
    }
    return VideoSimilarity.dHash(gray)
}

/** Videos y fotos que se VEN igual aunque no sean idénticos byte por byte. */
class SimilarMedia(private val context: Context, private val progress: Progress) {

    // ---------- Videos parecidos ----------

    private class VideoSig(val durationMs: Long, val width: Int, val height: Int) {
        /** Huella de cada fotograma; [computed] dice si ya se calculó (null = liso o ilegible). */
        val hashes = arrayOfNulls<Long>(VideoSimilarity.FRAME_POSITIONS.size)
        val computed = BooleanArray(VideoSimilarity.FRAME_POSITIONS.size)
        fun list(): List<Long?> = hashes.toList()
    }

    private val sigCacheFile = File(context.cacheDir, "video_sigs_v2.tsv")
    private val sigCache = java.util.concurrent.ConcurrentHashMap<String, VideoSig>()

    private fun sigKey(r: FileRec) = "${r.path}|${r.size}|${r.modified}"

    private fun loadSigCache() {
        try {
            if (!sigCacheFile.exists()) return
            sigCacheFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size != 5) return@forEachLine
                val sig = VideoSig(p[1].toLong(), p[2].toInt(), p[3].toInt())
                p[4].split(',').forEachIndexed { k, v ->
                    if (k < sig.hashes.size && v != "x") {
                        sig.computed[k] = true
                        sig.hashes[k] = v.toLongOrNull()
                    }
                }
                sigCache[p[0]] = sig
            }
        } catch (_: Exception) {
            sigCache.clear()
        }
    }

    private fun saveSigCache(keep: Set<String>) {
        try {
            sigCacheFile.bufferedWriter().use { w ->
                for ((k, v) in sigCache) {
                    if (k !in keep) continue
                    val h = v.hashes.indices.joinToString(",") { i ->
                        if (!v.computed[i]) "x" else v.hashes[i]?.toString() ?: "-"
                    }
                    w.write("$k\t${v.durationMs}\t${v.width}\t${v.height}\t$h\n")
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Duración y tamaño de todos los videos en UNA consulta a la base de medios de Android. */
    private fun mediaStoreMeta(): Map<String, VideoSig> {
        val out = HashMap<String, VideoSig>()
        try {
            @Suppress("DEPRECATION")
            val cols = arrayOf(
                android.provider.MediaStore.Video.Media.DATA,
                android.provider.MediaStore.Video.Media.DURATION,
                android.provider.MediaStore.Video.Media.WIDTH,
                android.provider.MediaStore.Video.Media.HEIGHT,
            )
            context.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    val d = c.getLong(1); val w = c.getInt(2); val h = c.getInt(3)
                    if (d > 0 && w > 0 && h > 0) out[path] = VideoSig(d, w, h)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * Comparación visual, pensada para correr en segundo plano después del análisis principal.
     * Primero compara solo el fotograma del medio; los otros dos se sacan únicamente de los
     * pares que ya coinciden en ese fotograma.
     */
    suspend fun similarVideos(videos: List<FileRec>): List<CleanItem> = withContext(Dispatchers.IO) {
        if (videos.size < 2) return@withContext emptyList()
        val io = Dispatchers.IO.limitedParallelism(4)
        loadSigCache()
        val keep = videos.map { sigKey(it) }.toSet()
        try {
            // 1) Metadatos: caché → base de medios → abrir el video (solo si falta).
            progress.start("Leyendo videos")
            val store = mediaStoreMeta()
            val missing = videos.filter { sigCache[sigKey(it)] == null && store[it.path] == null }
            progress.total.set(missing.size)
            for (r in videos) {
                if (sigCache[sigKey(r)] == null) store[r.path]?.let { sigCache[sigKey(r)] = it }
            }
            missing.chunked(8).map { chunk ->
                async(io) {
                    for (r in chunk) {
                        ensureActive()
                        readMeta(r)?.let { sigCache[sigKey(r)] = it }
                        progress.done.incrementAndGet()
                    }
                }
            }.awaitAll()

            val metas = ArrayList<VideoSimilarity.Meta>()
            val sigs = ArrayList<VideoSig>()
            for (r in videos) {
                val s = sigCache[sigKey(r)] ?: continue
                if (s.durationMs < 1000) continue
                metas += VideoSimilarity.Meta(r, s.durationMs, s.width, s.height)
                sigs += s
            }

            // 2) Fotograma del medio para todos los que tienen pareja posible.
            val middle = VideoSimilarity.FRAME_POSITIONS.indexOf(0.5)
            var pairs = VideoSimilarity.candidatePairs(metas)
            computeFrames(pairs, metas, sigs, listOf(middle), "Comparando videos", io)
            pairs = pairs.filter { (a, b) ->
                val x = sigs[a].hashes[middle]; val y = sigs[b].hashes[middle]
                // Si el fotograma es liso (negro) no descarta: se decide con los otros dos.
                x == null || y == null ||
                    java.lang.Long.bitCount(x xor y) <= VideoSimilarity.MAX_FRAME_DISTANCE
            }

            // 3) Los otros fotogramas solo para los pares que siguen en pie.
            val others = VideoSimilarity.FRAME_POSITIONS.indices.filter { it != middle }
            computeFrames(pairs, metas, sigs, others, "Confirmando videos parecidos", io)

            val confirmed = HashMap<Pair<Int, Int>, Int>()
            for (p in pairs) {
                VideoSimilarity.distance(sigs[p.first].list(), sigs[p.second].list())
                    ?.let { confirmed[p] = it }
            }
            val groups = VideoSimilarity.groups(metas.size, confirmed.keys.toList())

            fun bestPercent(i: Int): Int {
                val best = confirmed.filterKeys { it.first == i || it.second == i }.values.minOrNull() ?: 64
                return VideoSimilarity.similarityPercent(best)
            }

            val result = ArrayList<CleanItem>()
            groups.map { g -> g.map { metas[it] to it } }
                .sortedByDescending { g -> g.sumOf { it.first.rec.size } - g.maxOf { it.first.rec.size } }
                .forEachIndexed { gi, g ->
                    g.sortedBy { it.first.rec.modified }.forEachIndexed { k, (m, idx) ->
                        val secs = m.durationMs / 1000
                        result += CleanItem(
                            m.rec.path, m.rec.size, false, m.rec.modified,
                            preselected = false,
                            pixels = m.width.toLong() * m.height,
                            detail = "Parecido ${bestPercent(idx)}% · " +
                                "%d:%02d · %dx%d".format(secs / 60, secs % 60, m.width, m.height),
                            group = gi,
                        )
                    }
                }
            result
        } finally {
            // Se guarda aunque se cancele, para no repetir el trabajo ya hecho.
            saveSigCache(keep)
        }
    }

    private suspend fun computeFrames(
        pairs: List<Pair<Int, Int>>,
        metas: List<VideoSimilarity.Meta>,
        sigs: List<VideoSig>,
        frames: List<Int>,
        label: String,
        io: kotlinx.coroutines.CoroutineDispatcher,
    ) = coroutineScope {
        val todo = pairs.flatMap { listOf(it.first, it.second) }.distinct()
            .filter { i -> frames.any { !sigs[i].computed[it] } }
        progress.start(label, todo.size)
        todo.chunked(4).map { chunk ->
            async(io) {
                for (i in chunk) {
                    ensureActive()
                    val sig = sigs[i]
                    val need = frames.filter { !sig.computed[it] }
                    val hs = frameHashes(metas[i].rec.path, sig.durationMs, need)
                    need.forEachIndexed { k, f ->
                        sig.hashes[f] = hs[k]
                        sig.computed[f] = true
                    }
                    progress.done.incrementAndGet()
                }
            }
        }.awaitAll()
    }

    private fun readMeta(r: FileRec): VideoSig? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(r.path)
            val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (d == null || w == null || h == null || w <= 0 || h <= 0) null else VideoSig(d, w, h)
        } catch (e: Exception) {
            null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    /** Huella de los fotogramas pedidos: cada uno se reduce a 9x8 en grises y se calcula su dHash. */
    private fun frameHashes(path: String, durationMs: Long, frames: List<Int>): List<Long?> {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            frames.map { k ->
                val timeUs = (durationMs * VideoSimilarity.FRAME_POSITIONS[k] * 1000).toLong()
                try {
                    val frame = if (Build.VERSION.SDK_INT >= 27) {
                        mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 64, 64)
                    } else {
                        mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } ?: return@map null
                    bitmapDHash(frame).also { frame.recycle() }
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            List(frames.size) { null }
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }


    // ---------- Fotos parecidas ----------

    private class PhotoSig(val ratio: Double, val hash: Long?, val width: Int = 0, val height: Int = 0)

    private val photoCacheFile = File(context.cacheDir, "photo_sigs_v2.tsv")
    private val photoCache = java.util.concurrent.ConcurrentHashMap<String, PhotoSig>()

    private fun loadPhotoCache() {
        try {
            if (!photoCacheFile.exists()) return
            photoCacheFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 5) {
                    photoCache[p[0]] = PhotoSig(p[1].toDouble(), p[2].toLongOrNull(), p[3].toInt(), p[4].toInt())
                }
            }
        } catch (_: Exception) {
            photoCache.clear()
        }
    }

    private fun savePhotoCache(keep: Set<String>) {
        try {
            photoCacheFile.bufferedWriter().use { w ->
                for ((k, v) in photoCache) {
                    if (k in keep) w.write("$k\t${v.ratio}\t${v.hash ?: "-"}\t${v.width}\t${v.height}\n")
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Fotos parecidas: ráfagas, capturas repetidas, fotos reenviadas o recomprimidas.
     * Cada foto se decodifica en miniatura (rápido) respetando su rotación.
     */
    suspend fun similarPhotos(photos: List<FileRec>): List<CleanItem> = withContext(Dispatchers.IO) {
        if (photos.size < 2) return@withContext emptyList()
        loadPhotoCache()
        val keep = photos.map { sigKey(it) }.toSet()
        val io = Dispatchers.IO.limitedParallelism(4)
        try {
            val todo = photos.filter { photoCache[sigKey(it)] == null }
            progress.start("Comparando fotos", todo.size)
            todo.chunked(16).map { chunk ->
                async(io) {
                    for (r in chunk) {
                        ensureActive()
                        photoCache[sigKey(r)] = photoSig(r.path) ?: PhotoSig(0.0, null)
                        progress.done.incrementAndGet()
                    }
                }
            }.awaitAll()

            progress.start("Agrupando fotos parecidas")
            val sigs = photos.map { photoCache[sigKey(it)] ?: PhotoSig(0.0, null) }
            val pairs = VideoSimilarity.photoPairs(sigs.map { it.hash }, sigs.map { it.ratio })
            val dist = HashMap<Pair<Int, Int>, Int>()
            pairs.forEach { (a, b, d) -> dist[a to b] = d }
            val groups = VideoSimilarity.groups(photos.size, dist.keys.toList())

            fun bestPercent(i: Int): Int {
                val best = dist.filterKeys { it.first == i || it.second == i }.values.minOrNull() ?: 64
                return VideoSimilarity.similarityPercent(best)
            }

            val result = ArrayList<CleanItem>()
            groups.sortedByDescending { g -> g.sumOf { photos[it].size } - g.maxOf { photos[it].size } }
                .forEachIndexed { gi, g ->
                    g.sortedBy { photos[it].modified }.forEach { i ->
                        val r = photos[i]
                        val sig = sigs[i]
                        result += CleanItem(
                            r.path, r.size, false, r.modified, preselected = false,
                            detail = "${sig.width}x${sig.height} · Parecida ${bestPercent(i)}%",
                            pixels = sig.width.toLong() * sig.height,
                            group = gi,
                        )
                    }
                }
            result
        } finally {
            savePhotoCache(keep)
        }
    }

    private fun photoSig(path: String): PhotoSig? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth; val h = bounds.outHeight
        if (w <= 0 || h <= 0) null else {
            var sample = 1
            while (minOf(w, h) / (sample * 2) >= 64) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp == null) null else {
                val rotated = applyExif(path, bmp)
                val hash = bitmapDHash(rotated)
                if (rotated !== bmp) rotated.recycle()
                bmp.recycle()
                PhotoSig(minOf(w, h).toDouble() / maxOf(w, h), hash, w, h)
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun applyExif(path: String, bmp: Bitmap): Bitmap {
        val degrees = try {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
