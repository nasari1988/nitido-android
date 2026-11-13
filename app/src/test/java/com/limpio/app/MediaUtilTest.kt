package com.limpio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaUtilTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun formatoDeTiempo() {
        assertEquals("0:00", MediaUtil.fmtTime(0))
        assertEquals("1:15", MediaUtil.fmtTime(75_000))
        assertEquals("1:02:03", MediaUtil.fmtTime(3_723_000))
        assertEquals("0:00", MediaUtil.fmtTime(-5))
    }

    @Test
    fun subtitulosJuntoAlVideo() {
        val d = tmp.newFolder("Movies")
        val v = File(d, "Viaje.mp4").apply { writeText("v") }
        assertNull(MediaUtil.findSubtitle(v))
        File(d, "Viaje.es.srt").writeText("s")
        assertEquals("Viaje.es.srt", MediaUtil.findSubtitle(v)?.name)
        File(d, "Viaje.srt").writeText("s")
        assertEquals("Viaje.srt", MediaUtil.findSubtitle(v)?.name) // el exacto tiene prioridad
        File(d, "Viajero.srt").writeText("s")
        assertEquals("Viaje.srt", MediaUtil.findSubtitle(v)?.name)
    }

    @Test
    fun agruparBiblioteca() {
        val t = listOf(
            Track("/m/a/1.mp3", "Luz", "Río Norte", "Verano", 200_000),
            Track("/m/a/2.mp3", "Arena", "Río Norte", "Verano", 180_000),
            Track("/m/b/3.mp3", "Calle", "Kora", "Ciudad", 210_000),
            Track("/m/b/4.mp3", "Noche", "Río Norte", "Invierno", 190_000),
        )
        val albums = MediaUtil.albums(t)
        assertEquals(listOf("Ciudad", "Invierno", "Verano"), albums.map { it.name })
        assertEquals(listOf("Arena", "Luz"), albums.last().tracks.map { it.title })
        val artists = MediaUtil.artists(t)
        assertEquals("2 álbumes · 3 canciones", artists.first { it.name == "Río Norte" }.subtitle)
        assertEquals(listOf("a", "b"), MediaUtil.folders(t).map { it.name })
        assertEquals("Artista desconocido", MediaUtil.unknown("<unknown>", "Artista desconocido"))
    }
}
