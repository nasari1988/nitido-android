package com.limpio.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class DuplicateFinderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(dir: File, name: String, bytes: ByteArray, modified: Long): FileRec {
        val f = File(dir, name)
        f.writeBytes(bytes)
        f.setLastModified(modified)
        return FileRec(f.absolutePath, f.length(), f.lastModified())
    }

    @Test
    fun dosVideosIgualesConDistintoNombre() = runBlocking {
        val movies = tmp.newFolder("Movies")
        val video = Random(1).nextBytes(5 * 1024 * 1024)
        val original = write(movies, "vacaciones.mp4", video, 1_000_000_000_000)
        val copia = write(movies, "otro nombre.mp4", video, 1_700_000_000_000)
        // Mismo tamaño pero contenido distinto: no debe salir.
        val distinto = write(movies, "distinto.mp4", Random(2).nextBytes(video.size), 1_600_000_000_000)

        val result = DuplicateFinder.find(listOf(copia, distinto, original))

        assertEquals(2, result.size)
        val keep = result.single { !it.preselected }
        val delete = result.single { it.preselected }
        assertEquals(original.path, keep.path)   // se conserva el más antiguo
        assertEquals(copia.path, delete.path)
        assertTrue(keep.note!!.startsWith("Original"))
        assertFalse(result.any { it.path == distinto.path })
    }

    @Test
    fun mismoInicioYFinalPeroDistintoEnMedio() = runBlocking {
        val dir = tmp.newFolder("d")
        val a = Random(3).nextBytes(512 * 1024)
        val b = a.copyOf()
        b[256 * 1024] = (b[256 * 1024] + 1).toByte()  // cambia solo un byte del medio
        val ra = write(dir, "a.bin", a, 1)
        val rb = write(dir, "b.bin", b, 2)
        assertTrue(DuplicateFinder.find(listOf(ra, rb)).isEmpty())
    }

    @Test
    fun archivosPequenosIdenticosYTresCopias() = runBlocking {
        val dir = tmp.newFolder("p")
        val data = Random(4).nextBytes(10 * 1024)
        val recs = (1..3).map { write(dir, "foto$it.jpg", data, it * 1000L) }
        val result = DuplicateFinder.find(recs.reversed())
        assertEquals(3, result.size)
        assertEquals(2, result.count { it.preselected })
        assertEquals(1, result.map { it.group }.distinct().size)
    }
}
