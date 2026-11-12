package com.limpio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VideoSimilarityTest {

    private fun meta(i: Int, durationMs: Long, w: Int = 1080, h: Int = 1920) =
        VideoSimilarity.Meta(FileRec("/v$i.mp4", 1_000_000, i.toLong()), durationMs, w, h)

    /** Una "escena" aleatoria de 9x8 en grises. */
    private fun scene(seed: Int): IntArray {
        val r = Random(seed)
        return IntArray(72) { r.nextInt(256) }
    }

    /** La misma escena con un poco de ruido, como al recodificar. */
    private fun reencoded(g: IntArray, seed: Int): IntArray {
        val r = Random(seed)
        return IntArray(72) { (g[it] + r.nextInt(-3, 4)).coerceIn(0, 255) }
    }

    private fun sig(vararg frames: IntArray) = frames.map { VideoSimilarity.dHash(it) }

    @Test
    fun reelsDeMismaDuracionPeroDistintoContenidoNoSonParecidos() {
        // 30 reels distintos, todos de 15 s y verticales: el caso de la carpeta de reels.
        val sigs = (0 until 30).map { sig(scene(it * 3), scene(it * 3 + 1), scene(it * 3 + 2)) }
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (i in sigs.indices) for (j in i + 1 until sigs.size) {
            if (VideoSimilarity.distance(sigs[i], sigs[j]) != null) pairs += i to j
        }
        assertTrue("No debería haber falsos parecidos, hubo ${pairs.size}", pairs.isEmpty())
    }

    @Test
    fun mismoVideoRecodificadoSiEsParecido() {
        val a = listOf(scene(1), scene(2), scene(3))
        val b = a.mapIndexed { i, g -> reencoded(g, 50 + i) }
        val d = VideoSimilarity.distance(sig(*a.toTypedArray()), sig(*b.toTypedArray()))
        assertNotNull(d)
        assertTrue(VideoSimilarity.similarityPercent(d!!) >= 85)
    }

    @Test
    fun fotogramasNegrosNoCuentan() {
        val black = IntArray(72) { 2 }
        assertNull(VideoSimilarity.dHash(black))
        // Dos videos que solo coinciden en fotogramas negros no son parecidos.
        assertNull(VideoSimilarity.distance(sig(black, black, scene(1)), sig(black, black, scene(9))))
    }

    @Test
    fun candidatosPorDuracionYProporcion() {
        val metas = listOf(
            meta(0, 15_000),
            meta(1, 15_600),                 // ±1 s, vertical → candidato con 0
            meta(2, 15_200, 1920, 1080),     // horizontal pero misma proporción → candidato
            meta(3, 15_100, 1080, 1080),     // cuadrado → no
            meta(4, 30_000),                 // otra duración → no
        )
        val pairs = VideoSimilarity.candidatePairs(metas).toSet()
        assertTrue((0 to 1) in pairs)
        assertTrue((0 to 2) in pairs)
        assertTrue(pairs.none { it.first == 3 || it.second == 3 })
        assertTrue(pairs.none { it.first == 4 || it.second == 4 })
    }

    @Test
    fun gruposTransitivos() {
        val g = VideoSimilarity.groups(5, listOf(0 to 1, 1 to 2))
        assertEquals(1, g.size)
        assertEquals(listOf(0, 1, 2), g.first().sorted())
    }
}
