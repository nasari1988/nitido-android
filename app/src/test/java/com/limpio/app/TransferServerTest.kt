package com.limpio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket

class TransferServerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun computadoraDePuntaAPunta() {
        val root = tmp.newFolder("telefono")
        val port = ServerSocket(0).use { it.localPort }
        var ro = false
        val server = TransferServer("127.0.0.1", port, root, "482913", readOnly = { ro })
        server.start(5000, true)
        try {
            val fails = TransferSelfCheck.run(port, "482913", root) { ro = it }
            assertEquals(emptyList<String>(), fails)
        } finally {
            server.stop()
        }
    }

    @Test
    fun noSePuedeSalirDeLaRaiz() {
        val root = tmp.newFolder("raiz")
        val s = TransferServer(null, 1, root, "1", readOnly = { false })
        assertNull(s.resolve("../x"))
        assertNull(s.resolve("a/../../x"))
        assertEquals(root.canonicalFile, s.resolve(""))
        assertEquals(java.io.File(root, "a").canonicalFile, s.resolve("/a/"))
    }

    @Test
    fun escapaHtml() {
        assertEquals("&lt;script&gt;&quot;x&quot;&amp;&#39;", TransferServer.esc("<script>\"x\"&'"))
    }
}

class ZipFolderStreamTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun zipAlVueloSeLeeCompleto() {
        val d = tmp.newFolder("Viaje")
        val big = kotlin.random.Random(3).nextBytes(300_000)
        java.io.File(d, "grande.bin").writeBytes(big)
        java.io.File(d, "sub").mkdirs()
        java.io.File(d, "sub/nota.txt").writeText("hola")
        java.io.File(d, "vacio.txt").writeText("")
        val files = d.walkTopDown().filter { it.isFile }.map { it to "Viaje/" + it.relativeTo(d).invariantSeparatorsPath }.toList()
        val bytes = ZipFolderStream(files).use { it.readBytes() }
        val got = HashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; got[e.name] = z.readBytes() }
        }
        assertEquals(setOf("Viaje/grande.bin", "Viaje/sub/nota.txt", "Viaje/vacio.txt"), got.keys)
        org.junit.Assert.assertArrayEquals(big, got["Viaje/grande.bin"])
        assertEquals("hola", String(got.getValue("Viaje/sub/nota.txt")))
    }
}
