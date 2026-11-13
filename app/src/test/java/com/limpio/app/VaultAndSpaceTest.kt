package com.limpio.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

class VaultAndSpaceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun roundTrip(size: Int) {
        val key = VaultCrypto.newKey()
        val data = Random(size).nextBytes(size)
        val enc = ByteArrayOutputStream()
        val sha1 = VaultCrypto.encrypt(key, ByteArrayInputStream(data), enc)
        val dec = ByteArrayOutputStream()
        val sha2 = VaultCrypto.decrypt(key, ByteArrayInputStream(enc.toByteArray()), dec)
        assertArrayEquals("tamaño $size", data, dec.toByteArray())
        assertArrayEquals(sha1, sha2)
        // Lo cifrado no contiene el original en claro
        if (size >= 64) assertFalse(String(enc.toByteArray(), Charsets.ISO_8859_1).contains(String(data.copyOf(64), Charsets.ISO_8859_1)))
    }

    @Test
    fun cifrarYDescifrarDistintosTamanos() {
        listOf(0, 1, 1000, VaultCrypto.CHUNK - 1, VaultCrypto.CHUNK, VaultCrypto.CHUNK + 1, 3 * VaultCrypto.CHUNK + 777).forEach { roundTrip(it) }
    }

    private fun expectFailure(bytes: ByteArray, key: javax.crypto.SecretKey, what: String) {
        try {
            VaultCrypto.decrypt(key, ByteArrayInputStream(bytes), ByteArrayOutputStream())
            fail("Debió detectar: $what")
        } catch (e: Exception) {
            // correcto
        }
    }

    @Test
    fun detectaAlteracionesRecortesYLlaveEquivocada() {
        val key = VaultCrypto.newKey()
        val data = Random(9).nextBytes(2 * VaultCrypto.CHUNK + 50)
        val enc = VaultCrypto.encryptBytes(key, data)

        expectFailure(enc.copyOf().also { it[enc.size / 2] = (it[enc.size / 2] + 1).toByte() }, key, "un byte alterado")
        // Quitar el último bloque completo (1 + 4 + 50 + 16 bytes)
        expectFailure(enc.copyOf(enc.size - (1 + 4 + 50 + 16)), key, "archivo recortado")
        expectFailure(enc + byteArrayOf(1, 2, 3), key, "datos de más")
        expectFailure(enc, VaultCrypto.newKey(), "llave equivocada")
        assertArrayEquals(data, VaultCrypto.decryptBytes(key, enc))
    }

    @Test
    fun mapaDeBloquesProporcionalYDentroDelArea() {
        val values = listOf(500.0, 300.0, 120.0, 50.0, 20.0, 10.0)
        val w = 400f; val h = 300f
        val rects = Treemap.squarify(values, 0f, 0f, w, h)
        assertEquals(values.size, rects.size)
        val total = values.sum()
        rects.forEachIndexed { i, r ->
            val expected = values[i] / total * w * h
            assertTrue("área $i", abs(r.w * r.h - expected) < expected * 0.01 + 1)
            assertTrue(r.x >= -0.01f && r.y >= -0.01f && r.x + r.w <= w + 0.5f && r.y + r.h <= h + 0.5f)
        }
        assertTrue(abs(rects.sumOf { (it.w * it.h).toDouble() } - w * h) < 2)
    }

    @Test
    fun tamanosDeCarpetas() {
        val root = tmp.newFolder("raiz")
        File(root, "a/b").mkdirs()
        File(root, "a/x.bin").writeBytes(ByteArray(100))
        File(root, "a/b/y.bin").writeBytes(ByteArray(250))
        File(root, "z.bin").writeBytes(ByteArray(7))
        File(root, "sistema").mkdirs()
        File(root, "sistema/oculto.bin").writeBytes(ByteArray(999))
        var files = 0
        val sizes = SpaceAnalyzer.folderSizes(root, skip = { it.name == "sistema" }) { files++ }
        assertEquals(357L, sizes[root.absolutePath])
        assertEquals(350L, sizes[File(root, "a").absolutePath])
        assertEquals(250L, sizes[File(root, "a/b").absolutePath])
        assertEquals(3, files)
    }
}
