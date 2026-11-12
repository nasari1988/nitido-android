package com.limpio.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
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

    private class FileRec(val path: String, val size: Long, val modified: Long)

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
        val duplicates = findDuplicates(all)

        val bySizeDesc = compareByDescending<CleanItem> { it.size }
        val items = linkedMapOf<Category, List<CleanItem>>()
        items[Category.CACHE] = cache.sortedWith(bySizeDesc)
        items[Category.LEFTOVERS] = leftovers.sortedWith(bySizeDesc)
        items[Category.EMPTY] = empties.sortedBy { it.path }
        items[Category.APKS] = apks.sortedWith(bySizeDesc)
        items[Category.DUPLICATES] = duplicates
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

    // ---------- Duplicados ----------

    private suspend fun findDuplicates(all: List<FileRec>): List<CleanItem> = coroutineScope {
        val io = Dispatchers.IO.limitedParallelism(4)

        // Paso 1: solo comparan archivos del mismo tamaño (no lee nada del disco).
        val sameSize = all.asSequence()
            .filter { it.size >= MIN_DUP_SIZE }
            .groupBy { it.size }
            .values.filter { it.size > 1 }
            .flatten()

        // Paso 2: hash de los primeros y últimos 32 KB.
        val partial = sameSize.chunked(64).map { chunk ->
            async(io) { chunk.mapNotNull { r -> partialHash(r)?.let { r to it } } }
        }.awaitAll().flatten()
        val partialGroups = partial.groupBy({ "${it.first.size}:${it.second}" }, { it.first })
            .values.filter { it.size > 1 }

        // Paso 3: hash completo, solo si el archivo es más grande que lo ya leído.
        val needFull = partialGroups.flatten().filter { it.size > 2 * PARTIAL_BYTES }
        val fullHashes = needFull.chunked(16).map { chunk ->
            async(io) { chunk.mapNotNull { r -> fullHash(r)?.let { r.path to it } } }
        }.awaitAll().flatten().toMap()

        val confirmed = ArrayList<List<FileRec>>()
        for (g in partialGroups) {
            if (g.first().size <= 2 * PARTIAL_BYTES) {
                confirmed += g // el hash parcial ya cubrió todo el archivo
            } else {
                g.filter { fullHashes.containsKey(it.path) }
                    .groupBy { fullHashes.getValue(it.path) }
                    .values.filter { it.size > 1 }
                    .forEach { confirmed += it }
            }
        }

        // Ordenar por espacio desperdiciado; conservar la copia más antigua de cada grupo.
        val result = ArrayList<CleanItem>()
        confirmed.sortedByDescending { it.first().size * (it.size - 1) }
            .forEachIndexed { gi, group ->
                group.sortedBy { it.modified }.forEachIndexed { i, r ->
                    result += CleanItem(
                        r.path, r.size, false, r.modified,
                        preselected = i > 0,
                        note = if (i == 0) "Original · se conserva" else "Copia",
                        group = gi,
                    )
                }
            }
        result
    }

    private fun partialHash(r: FileRec): String? = try {
        val md = MessageDigest.getInstance("MD5")
        RandomAccessFile(r.path, "r").use { raf ->
            val buf = ByteArray(PARTIAL_BYTES)
            val len = raf.length()
            var n = raf.read(buf, 0, minOf(PARTIAL_BYTES.toLong(), len).toInt())
            if (n > 0) md.update(buf, 0, n)
            if (len > PARTIAL_BYTES) {
                val tailStart = maxOf(PARTIAL_BYTES.toLong(), len - PARTIAL_BYTES)
                raf.seek(tailStart)
                n = raf.read(buf, 0, (len - tailStart).toInt())
                if (n > 0) md.update(buf, 0, n)
            }
        }
        md.digest().toHex()
    } catch (e: Exception) {
        null
    }

    private fun fullHash(r: FileRec): String? = try {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(r.path).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().toHex()
    } catch (e: Exception) {
        null
    }

    private fun ByteArray.toHex(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(hex[v ushr 4]).append(hex[v and 0x0f])
        }
        return sb.toString()
    }

    companion object {
        private const val MAX_DEPTH = 48
        private const val PARTIAL_BYTES = 32 * 1024
        private const val MIN_DUP_SIZE = 4L * 1024

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
