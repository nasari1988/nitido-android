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

/** Reproduce lo que pasó en el teléfono: una subida rechazada y otra petición por el MISMO socket. */
class KeepAliveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun peticionTrasRechazoEnLaMismaConexion() {
        val root = tmp.newFolder("t")
        java.io.File(root, "a.txt").writeText("hola")
        val port = java.net.ServerSocket(0).use { it.localPort }
        val server = TransferServer("127.0.0.1", port, root, "123456", readOnly = { true })
        server.start(5000, true)
        try {
            java.net.Socket("127.0.0.1", port).use { sock ->
                sock.soTimeout = 5000
                val out = sock.getOutputStream()
                val inp = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream()))
                fun readResponse(): Pair<Int, Map<String, String>> {
                    val status = inp.readLine().split(" ")[1].toInt()
                    val headers = HashMap<String, String>()
                    while (true) {
                        val l = inp.readLine() ?: break
                        if (l.isEmpty()) break
                        headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim()
                    }
                    val len = headers["content-length"]?.toInt() ?: 0
                    repeat(len) { inp.read() }
                    return status to headers
                }
                // 1) Entrar
                val form = "code=123456"
                out.write("POST /login HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${form.length}\r\n\r\n$form".toByteArray())
                val (s1, h1) = readResponse()
                assertEquals(303, s1)
                val cookie = h1.getValue("set-cookie").substringBefore(';')
                // 2) Subida rechazada (solo lectura) con cuerpo grande
                val body = "--b\r\nContent-Disposition: form-data; name=\"f\"; filename=\"x.bin\"\r\n\r\n" + "x".repeat(20000) + "\r\n--b--\r\n"
                out.write("POST /up?ajax=1&p= HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nContent-Type: multipart/form-data; boundary=b\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                assertEquals(403, readResponse().first)
                // 3) Otra petición por el mismo socket
                out.write("GET /dl?p=a.txt HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n".toByteArray())
                assertEquals(200, readResponse().first)
            }
        } finally {
            server.stop()
        }
    }
}
