package com.limpio.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

class FileRec(val path: String, val size: Long, val modified: Long)

/**
 * Duplicados exactos (byte por byte), en tres pasos de barato a caro:
 * mismo tamaño → hash de los primeros y últimos 32 KB → hash completo.
 * No usa nada de Android, así que se prueba en la compilación con archivos reales.
 */
object DuplicateFinder {
    const val PARTIAL_BYTES = 32 * 1024
    const val MIN_DUP_SIZE = 4L * 1024


    suspend fun find(
        all: List<FileRec>,
        onTotal: (Int) -> Unit = {},
        onStep: () -> Unit = {},
    ): List<CleanItem> = coroutineScope {
        val io = Dispatchers.IO.limitedParallelism(4)

        // Paso 1: solo comparan archivos del mismo tamaño (no lee nada del disco).
        val sameSize = all.asSequence()
            .filter { it.size >= MIN_DUP_SIZE }
            .groupBy { it.size }
            .values.filter { it.size > 1 }
            .flatten()
        onTotal(sameSize.size)

        // Paso 2: hash de los primeros y últimos 32 KB.
        val partial = sameSize.chunked(64).map { chunk ->
            async(io) { chunk.mapNotNull { r -> partialHash(r).also { onStep() }?.let { r to it } } }
        }.awaitAll().flatten()
        val partialGroups = partial.groupBy({ "${it.first.size}:${it.second}" }, { it.first })
            .values.filter { it.size > 1 }

        // Paso 3: hash completo, solo si el archivo es más grande que lo ya leído.
        val needFull = partialGroups.flatten().filter { it.size > 2 * PARTIAL_BYTES }
        onTotal(needFull.size)
        val fullHashes = needFull.chunked(16).map { chunk ->
            async(io) { chunk.mapNotNull { r -> fullHash(r).also { onStep() }?.let { r.path to it } } }
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

}
