package com.limpio.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ZipOpsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun comprimirYExtraerConservaTodo() {
        val src = tmp.newFolder("Viaje")
        val foto = Random(1).nextBytes(300_000)
        File(src, "foto.jpg").writeBytes(foto)
        File(src, "sub").mkdirs()
        File(src, "sub/nota.txt").writeText("hola ñandú")
        val suelto = tmp.newFile("lista.txt").apply { writeText("pan") }

        val zip = File(tmp.root, "todo.zip")
        assertTrue(ZipOps.zip(listOf(src, suelto), zip))

        val out = tmp.newFolder("salida")
        assertEquals(3, ZipOps.unzip(zip, out))
        assertArrayEquals(foto, File(out, "Viaje/foto.jpg").readBytes())
        assertEquals("hola ñandú", File(out, "Viaje/sub/nota.txt").readText())
        assertEquals("pan", File(out, "lista.txt").readText())
    }

    @Test
    fun rechazaZipQueIntentaSalirDeLaCarpeta() {
        val evil = File(tmp.root, "malo.zip")
        ZipOutputStream(FileOutputStream(evil)).use { z ->
            z.putNextEntry(ZipEntry("../../fuera.txt"))
            z.write("x".toByteArray())
            z.closeEntry()
        }
        val dest = tmp.newFolder("dest")
        assertEquals(-1, ZipOps.unzip(evil, dest))
        assertFalse(File(tmp.root.parentFile, "fuera.txt").exists())
    }
}
