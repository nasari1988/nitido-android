package com.limpio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DupRulesTest {

    private fun item(path: String, modified: Long, group: Int = 0) =
        CleanItem(path, 1000, false, modified, preselected = true, group = group)

    @Test
    fun conservaLaDeLaCamaraSobreLaDeWhatsapp() {
        val g = listOf(
            item("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg", 1),
            item("/storage/emulated/0/DCIM/Camera/IMG_2024.jpg", 5),
            item("/storage/emulated/0/Download/IMG_2024 (1).jpg", 3),
        )
        assertEquals("/storage/emulated/0/DCIM/Camera/IMG_2024.jpg", Keep.pick(g, KeepRule.ORIGINAL))
        assertEquals(g[0].path, Keep.pick(g, KeepRule.OLDEST))
        assertEquals(g[1].path, Keep.pick(g, KeepRule.NEWEST))
    }

    @Test
    fun mejorCalidadPrefiereMayorResolucionYLuegoMasPeso() {
        val whatsapp = CleanItem("/x/WhatsApp/Media/VID-1.mp4", 3_000_000, false, 1, true, pixels = 848L * 480)
        val camara = CleanItem("/x/DCIM/Camera/VID_1.mp4", 40_000_000, false, 9, true, pixels = 1920L * 1080)
        val camaraLigera = CleanItem("/x/Movies/VID_1.mp4", 20_000_000, false, 5, true, pixels = 1920L * 1080)
        assertEquals(camara.path, Keep.pick(listOf(whatsapp, camaraLigera, camara), KeepRule.BEST))
        // Copias idénticas (misma resolución y peso): gana el original.
        val g = listOf(
            item("/storage/emulated/0/Download/a.jpg", 1),
            item("/storage/emulated/0/DCIM/Camera/a.jpg", 9),
        )
        assertEquals(g[1].path, Keep.pick(g, KeepRule.BEST))
    }

    @Test
    fun etiquetasYMarcado() {
        val g = listOf(item("/a/x.jpg", 1), item("/b/y.jpg", 2), item("/c/z.jpg", 3))
        val exact = Keep.label(g, "/b/y.jpg", markOthers = true, exact = true)
        assertEquals("/b/y.jpg", exact.first().path)               // el conservado va primero
        assertEquals(Keep.KEPT_TAG, exact.first().note)
        assertFalse(exact.first().preselected)
        assertTrue(exact.drop(1).all { it.preselected && it.note == "Copia" })

        val similar = Keep.label(g.map { it.copy(detail = "Parecida 97%") }, "/a/x.jpg", markOthers = false, exact = false)
        assertTrue(similar.none { it.preselected })
        assertEquals("${Keep.KEPT_TAG} · Parecida 97%", similar.first().note)
        assertEquals("Parecida 97%", similar[1].note)
    }

    @Test
    fun fotosParecidasSinCompararTodasContraTodas() {
        val r = Random(7)
        val base = List(200) { r.nextLong() }
        val hashes = base.toMutableList<Long?>()
        // Foto 0 recomprimida: 3 bits distintos. Foto 1 rotada a otra proporción: no cuenta.
        hashes += base[0] xor 0b1011L
        hashes += base[1] xor 0b1L
        hashes += null // foto lisa o ilegible
        val ratios = MutableList(hashes.size) { 0.75 }
        ratios[201] = 0.5625
        val pairs = VideoSimilarity.photoPairs(hashes, ratios)
        assertEquals(listOf(Triple(0, 200, 3)), pairs)
    }

    @Test
    fun paresSeEncuentranAunqueSoloCompartanUnByte() {
        // Distancia 6 repartida en 6 bytes distintos: solo 2 bytes iguales.
        val a = 0L
        var b = 0L
        for (k in 0 until 6) b = b or (1L shl (k * 8))
        val pairs = VideoSimilarity.photoPairs(listOf(a, b), listOf(0.75, 0.75))
        assertEquals(1, pairs.size)
        assertEquals(6, pairs.first().third)
    }
}
