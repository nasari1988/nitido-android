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
