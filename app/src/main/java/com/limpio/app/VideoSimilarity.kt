package com.limpio.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Detección de videos que se VEN igual aunque no sean idénticos byte por byte
 * (recodificados por WhatsApp, descargados dos veces, otra resolución…).
 *
 * 1. Candidatos baratos: duración ±1 s y misma proporción de pantalla.
 * 2. Confirmación visual: "huella" (dHash de 64 bits) de 3 fotogramas (20 %, 50 %, 80 %).
 *    Son parecidos solo si TODOS los fotogramas comparables coinciden.
 *
 * No usa nada de Android para poder probarse en la compilación.
 */
object VideoSimilarity {
    val FRAME_POSITIONS = listOf(0.2, 0.5, 0.8)

    /** Bits distintos permitidos por fotograma (de 64). Recodificar suele dar 0–6; otro video, 20+. */
    const val MAX_FRAME_DISTANCE = 10
    const val MAX_DURATION_DIFF_MS = 1000L
    const val MAX_RATIO_DIFF = 0.03

    class Meta(val rec: FileRec, val durationMs: Long, val width: Int, val height: Int) {
        /** Proporción sin importar si es vertical u horizontal. */
        val ratio: Double = min(width, height).toDouble() / max(width, height)
    }

    /** Pares (índices) con duración y proporción compatibles. */
    fun candidatePairs(metas: List<Meta>): List<Pair<Int, Int>> {
        val order = metas.indices.sortedBy { metas[it].durationMs }
        val pairs = ArrayList<Pair<Int, Int>>()
        for (a in order.indices) {
            val i = order[a]
            for (b in a + 1 until order.size) {
                val j = order[b]
                if (metas[j].durationMs - metas[i].durationMs > MAX_DURATION_DIFF_MS) break
                if (abs(metas[i].ratio - metas[j].ratio) <= MAX_RATIO_DIFF) {
                    pairs += min(i, j) to max(i, j)
                }
            }
        }
        return pairs
    }

    /**
     * dHash de una imagen de 9x8 en grises (72 valores 0–255, fila por fila).
     * Devuelve null si el fotograma es casi liso (negro, blanco…): no sirve para comparar.
     */
    fun dHash(gray: IntArray): Long? {
        require(gray.size == 72)
        if ((gray.maxOrNull() ?: 0) - (gray.minOrNull() ?: 0) < 12) return null
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (gray[y * 9 + x] > gray[y * 9 + x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    /**
     * Distancia promedio entre dos videos, o null si no son parecidos
     * (algún fotograma muy distinto, o menos de 2 fotogramas comparables).
     */
    fun distance(a: List<Long?>, b: List<Long?>): Int? {
        val dists = a.indices.mapNotNull { k ->
            val x = a[k]; val y = b.getOrNull(k)
            if (x == null || y == null) null else java.lang.Long.bitCount(x xor y)
        }
        if (dists.size < 2 || dists.any { it > MAX_FRAME_DISTANCE }) return null
        return dists.sum() / dists.size
    }

    /** Para fotos (un solo "fotograma") se exige más parecido que en video. */
    const val MAX_PHOTO_DISTANCE = 6

    /**
     * Pares de fotos parecidas sin comparar todas contra todas: si dos huellas difieren en
     * 6 bits o menos, al menos uno de sus 8 bytes es idéntico (principio del palomar).
     * Así solo se comparan fotos que comparten algún byte. Devuelve (i, j, distancia).
     */
    fun photoPairs(
        hashes: List<Long?>,
        ratios: List<Double>,
        maxDist: Int = MAX_PHOTO_DISTANCE,
    ): List<Triple<Int, Int, Int>> {
        fun byteOf(h: Long, k: Int) = ((h ushr (k * 8)) and 0xff).toInt()
        val out = ArrayList<Triple<Int, Int, Int>>()
        for (k in 0 until 8) {
            val buckets = HashMap<Int, MutableList<Int>>()
            hashes.forEachIndexed { i, h ->
                if (h != null) buckets.getOrPut(byteOf(h, k)) { ArrayList() }.add(i)
            }
            for (b in buckets.values) {
                for (x in b.indices) for (y in x + 1 until b.size) {
                    val i = b[x]; val j = b[y]
                    val hi = hashes[i]!!; val hj = hashes[j]!!
                    // Si ya coincidían en un byte anterior, ese par ya se revisó.
                    if ((0 until k).any { byteOf(hi, it) == byteOf(hj, it) }) continue
                    if (abs(ratios[i] - ratios[j]) > MAX_RATIO_DIFF) continue
                    val d = java.lang.Long.bitCount(hi xor hj)
                    if (d <= maxDist) out += Triple(min(i, j), max(i, j), d)
                }
            }
        }
        return out
    }

    fun similarityPercent(distance: Int): Int = 100 - distance * 100 / 64

    /** Une los pares confirmados en grupos (si A≈B y B≈C, los tres van juntos). */
    fun groups(n: Int, pairs: List<Pair<Int, Int>>): List<List<Int>> {
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }
        for ((a, b) in pairs) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        return (0 until n).groupBy { find(it) }.values.filter { it.size > 1 }
    }
}
