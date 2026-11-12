package com.limpio.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recorre el almacenamiento compartido UNA sola vez (en paralelo por carpeta raíz)
 * y clasifica todo en categorías. Los duplicados se detectan en tres pasos baratos→caros:
 * mismo tamaño → hash de inicio y final del archivo → hash completo.
 */
class Scanner(private val context: Context) {

    /** Archivos vistos hasta ahora; la UI lo consulta para mostrar el progreso. */
    val scanned = AtomicInteger(0)

    private val root: File = Environment.getExternalStorageDirectory()
    private val rootPath: String = root.absolutePath
    private val androidDataPath = "$rootPath/Android/data"
    private val androidMediaPath = "$rootPath/Android/media"
    private val androidObbPath = "$rootPath/Android/obb"
    private val downloadPrefix = "$rootPath/${Environment.DIRECTORY_DOWNLOADS}/"

    private val files = ConcurrentLinkedQueue<FileRec>()
    private val cache = ConcurrentLinkedQueue<CleanItem>()
    private val leftovers = ConcurrentLinkedQueue<CleanItem>()
    private val empties = ConcurrentLinkedQueue<CleanItem>()

    private lateinit var installedVersions: Map<String, Long>

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        installedVersions = loadInstalledPackages()

        // 1) Recorrido en paralelo de cada carpeta de primer nivel.
        val top = root.listFiles() ?: emptyArray()
        val io = Dispatchers.IO.limitedParallelism(4)
        val topEmpty = coroutineScope {
            top.map { c -> async(io) { c to handleEntry(c, rootPath, 1) } }.awaitAll()
        }
        for ((dir, isEmpty) in topEmpty) if (isEmpty) addEmptyDir(dir)
        ensureActive()

        // 2) Clasificar los archivos normales.
        val all = files.toList()
        val now = System.currentTimeMillis()
        val ninetyDays = TimeUnit.DAYS.toMillis(90)
        val large = ArrayList<CleanItem>()
        val oldDownloads = ArrayList<CleanItem>()
        val waSent = ArrayList<CleanItem>()
        val apks = ArrayList<CleanItem>()
        val pm = context.packageManager

        for (f in all) {
            val p = f.path
            if (f.size >= 100L * 1024 * 1024) {
                large += CleanItem(p, f.size, false, f.modified, preselected = false)
            }
            if (p.startsWith(downloadPrefix) && now - f.modified > ninetyDays) {
                oldDownloads += CleanItem(p, f.size, false, f.modified, preselected = false)
            }
            if (p.contains("WhatsApp") && p.contains("/Media/") && p.contains("/Sent/")) {
                waSent += CleanItem(p, f.size, false, f.modified, preselected = false)
            }
            if (p.endsWith(".apk", ignoreCase = true)) {
                apks += analyzeApk(pm, f)
            }
        }
        ensureActive()

        // 3) Duplicados.
        val duplicates = DuplicateFinder.find(all)
        ensureActive()
        val exact = duplicates.map { it.path }.toHashSet()
        val similar = findSimilarVideos(all.filter { it.path !in exact })

        val bySizeDesc = compareByDescending<CleanItem> { it.size }
        val items = linkedMapOf<Category, List<CleanItem>>()
        items[Category.CACHE] = cache.sortedWith(bySizeDesc)
        items[Category.LEFTOVERS] = leftovers.sortedWith(bySizeDesc)
        items[Category.EMPTY] = empties.sortedBy { it.path }
        items[Category.APKS] = apks.sortedWith(bySizeDesc)
        items[Category.DUPLICATES] = duplicates
        items[Category.SIMILAR_VIDEOS] = similar
        items[Category.WHATSAPP_SENT] = waSent.sortedWith(bySizeDesc)
        items[Category.OLD_DOWNLOADS] = oldDownloads.sortedWith(bySizeDesc)
        items[Category.LARGE] = large.sortedWith(bySizeDesc)

        ScanResult(items, scanned.get(), System.currentTimeMillis() - start)
    }

    // ---------- Recorrido ----------

    /** Procesa un archivo o carpeta. Devuelve true solo si es una carpeta efectivamente vacía. */
    private fun handleEntry(c: File, parentPath: String, depth: Int): Boolean {
        val path = c.absolutePath
        if (c.isDirectory) {
            // Android 11+ no permite leer Android/data de otras apps.
            if (path == androidDataPath) return false

            if ((parentPath == androidMediaPath || parentPath == androidObbPath) &&
                !installedVersions.containsKey(c.name)
            ) {
                val (size, count) = measure(c)
                if (count == 0) return true
                leftovers += CleanItem(path, size, true, c.lastModified(), true, "$count archivos")
                return false
            }

            if (c.name.lowercase() in CACHE_DIR_NAMES) {
                val (size, count) = measure(c)
                if (count == 0) return true
                cache += CleanItem(path, size, true, c.lastModified(), true, "Carpeta · $count archivos")
                return false
            }

            if (depth > MAX_DEPTH) return false
            return walk(c, depth)
        }

        // Archivo
        scanned.incrementAndGet()
        val name = c.name
        val size = c.length()
        val modified = c.lastModified()
        if (size == 0L) {
            // Los archivos ocultos vacíos (.nomedia, candados) cumplen una función: no se tocan.
            if (!name.startsWith(".")) {
                empties += CleanItem(path, 0, false, modified, true, "Archivo vacío")
            }
            return false
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in TEMP_EXTENSIONS) {
            cache += CleanItem(path, size, false, modified, true, "Temporal")
            return false
        }
        files += FileRec(path, size, modified)
        return false
    }

    private fun walk(dir: File, depth: Int): Boolean {
        val children = dir.listFiles() ?: return false // sin permiso: se trata como no vacía
        val dirPath = dir.absolutePath
        var empty = true
        val emptyDirs = ArrayList<File>()
        for (c in children) {
            if (handleEntry(c, dirPath, depth + 1)) emptyDirs += c else empty = false
        }
        // Solo se reporta la carpeta vacía de más arriba, no cada subcarpeta.
        if (!empty) emptyDirs.forEach { addEmptyDir(it) }
        return empty
    }

    private fun addEmptyDir(d: File) {
        val isProtected = d.parent == rootPath && d.name in PROTECTED_TOP_DIRS
        if (!isProtected) {
            empties += CleanItem(d.absolutePath, 0, true, d.lastModified(), true, "Carpeta vacía")
        }
    }

    /** Tamaño total y número de archivos de una carpeta, sin recursión. */
    private fun measure(dir: File): Pair<Long, Int> {
        var size = 0L
        var count = 0
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.addLast(c) else {
                    size += c.length()
                    count++
                    scanned.incrementAndGet()
                }
            }
        }
        return size to count
    }

    // ---------- APKs ----------

    private fun loadInstalledPackages(): Map<String, Long> =
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getInstalledPackages(0)
                .associate { it.packageName to PackageInfoCompat.getLongVersionCode(it) }
        } catch (e: Exception) {
            emptyMap()
        }

    private fun analyzeApk(pm: PackageManager, f: FileRec): CleanItem {
        val info = try {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(f.path, 0)
        } catch (e: Exception) {
            null
        }
        if (info == null) {
            return CleanItem(f.path, f.size, false, f.modified, true, "APK dañado o incompleto")
        }
        val label = info.applicationInfo?.let { ai ->
            ai.sourceDir = f.path
            ai.publicSourceDir = f.path
            try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { null }
        } ?: info.packageName
        val apkVersion = PackageInfoCompat.getLongVersionCode(info)
        val installed = installedVersions[info.packageName]
        return when {
            installed == null ->
                CleanItem(f.path, f.size, false, f.modified, false, "$label · no instalada")
            apkVersion <= installed ->
                CleanItem(f.path, f.size, false, f.modified, true, "$label · ya instalada")
            else ->
                CleanItem(f.path, f.size, false, f.modified, false, "$label · versión más nueva que la instalada")
        }
    }

    // ---------- Videos parecidos ----------

    private class VideoSig(val durationMs: Long, val width: Int, val height: Int, val hashes: List<Long?>?)

    /** Huellas guardadas entre análisis (clave: ruta, tamaño y fecha), para no volver a decodificar. */
    private val sigCacheFile = File(context.cacheDir, "video_sigs_v1.tsv")
    private val sigCache = java.util.concurrent.ConcurrentHashMap<String, VideoSig>()

    private fun sigKey(r: FileRec) = "${r.path}|${r.size}|${r.modified}"

    private fun loadSigCache() {
        try {
            if (!sigCacheFile.exists()) return
            sigCacheFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 5) {
                    val hashes = if (p[4] == "?") null else p[4].split(',').map { it.toLongOrNull() }
                    sigCache[p[0]] = VideoSig(p[1].toLong(), p[2].toInt(), p[3].toInt(), hashes)
                }
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
                    val h = v.hashes?.joinToString(",") { it?.toString() ?: "-" } ?: "?"
                    w.write("$k\t${v.durationMs}\t${v.width}\t${v.height}\t$h\n")
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun findSimilarVideos(all: List<FileRec>): List<CleanItem> = coroutineScope {
        val videos = all.filter { it.size >= 100 * 1024 && FileTypes.kindOf(it.path) == FileKind.VIDEO }
        if (videos.size < 2) return@coroutineScope emptyList()
        loadSigCache()
        val io = Dispatchers.IO.limitedParallelism(4)

        // 1) Duración y resolución (rápido, de los metadatos).
        val metas = videos.chunked(8).map { chunk ->
            async(io) {
                chunk.mapNotNull { r ->
                    val key = sigKey(r)
                    val sig = sigCache[key] ?: readMeta(r)?.also { sigCache[key] = it }
                    sig?.takeIf { it.durationMs >= 1000 && it.width > 0 && it.height > 0 }
                        ?.let { VideoSimilarity.Meta(r, it.durationMs, it.width, it.height) }
                }
            }
        }.awaitAll().flatten()

        // 2) Solo los que tienen pareja posible pasan a la comparación visual.
        val pairs = VideoSimilarity.candidatePairs(metas)
        val needed = pairs.flatMap { listOf(it.first, it.second) }.distinct()
        val hashes = HashMap<Int, List<Long?>>()
        needed.chunked(4).map { chunk ->
            async(io) {
                chunk.map { i ->
                    val m = metas[i]
                    val key = sigKey(m.rec)
                    val cached = sigCache[key]
                    val h = cached?.hashes ?: frameHashes(m.rec.path, m.durationMs).also {
                        sigCache[key] = VideoSig(m.durationMs, m.width, m.height, it)
                    }
                    i to h
                }
            }
        }.awaitAll().flatten().forEach { (i, h) -> hashes[i] = h }
        ensureActive()

        // 3) Confirmar pares que se ven igual y agruparlos.
        val confirmed = HashMap<Pair<Int, Int>, Int>()
        for (p in pairs) {
            val d = VideoSimilarity.distance(hashes[p.first].orEmpty(), hashes[p.second].orEmpty())
            if (d != null) confirmed[p] = d
        }
        val groups = VideoSimilarity.groups(metas.size, confirmed.keys.toList())
        saveSigCache(videos.map { sigKey(it) }.toSet())

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
                        note = (if (k == 0) "El más antiguo · " else "") +
                            "Parecido ${bestPercent(idx)}% · " +
                            "%d:%02d · %dx%d".format(secs / 60, secs % 60, m.width, m.height),
                        group = gi,
                    )
                }
            }
        result
    }

    private fun readMeta(r: FileRec): VideoSig? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(r.path)
            val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (d == null || w == null || h == null) null else VideoSig(d, w, h, null)
        } catch (e: Exception) {
            null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    /** Huella de 3 fotogramas: cada uno se reduce a 9x8 en grises y se calcula su dHash. */
    private fun frameHashes(path: String, durationMs: Long): List<Long?> {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            VideoSimilarity.FRAME_POSITIONS.map { f ->
                val timeUs = (durationMs * f * 1000).toLong()
                try {
                    val frame = if (Build.VERSION.SDK_INT >= 27) {
                        mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 96)
                    } else {
                        mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } ?: return@map null
                    val small = Bitmap.createScaledBitmap(frame, 9, 8, true)
                    val px = IntArray(72)
                    small.getPixels(px, 0, 9, 0, 0, 9, 8)
                    if (small !== frame) small.recycle()
                    frame.recycle()
                    val gray = IntArray(72) { k ->
                        val c = px[k]
                        (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
                    }
                    VideoSimilarity.dHash(gray)
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            List(VideoSimilarity.FRAME_POSITIONS.size) { null }
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val MAX_DEPTH = 48

        private val CACHE_DIR_NAMES = setOf(
            ".thumbnails", ".cache", "cache", ".tmp", "tmp", ".temp", "temp", "lost.dir"
        )
        private val TEMP_EXTENSIONS = setOf("tmp", "temp", "log", "dmp")

        /** Carpetas estándar de primer nivel que nunca se borran aunque estén vacías. */
        private val PROTECTED_TOP_DIRS = setOf(
            "Android", "DCIM", "Download", "Documents", "Pictures", "Music", "Movies",
            "Alarms", "Notifications", "Podcasts", "Ringtones", "Audiobooks", "Recordings"
        )
    }
}
