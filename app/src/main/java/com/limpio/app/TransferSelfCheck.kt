package com.limpio.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Prueba completa del servidor de transferencia como lo usaría una computadora:
 * entrar con el código, navegar, descargar, pedir un rango (video), subir, intentar salirse de la
 * carpeta y el modo solo lectura. Devuelve la lista de fallas (vacía = todo bien).
 */
object TransferSelfCheck {
    private class Resp(val code: Int, val body: ByteArray, val headers: Map<String, List<String>>) {
        val text get() = String(body)
        fun header(name: String) = headers.entries.firstOrNull { it.key?.equals(name, true) == true }?.value?.firstOrNull()
    }

    private fun call(
        url: String, method: String = "GET", cookie: String? = null,
        body: ByteArray? = null, contentType: String? = null, range: String? = null,
    ): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = method
        c.connectTimeout = 5000; c.readTimeout = 10000
        cookie?.let { c.setRequestProperty("Cookie", it) }
        c.setRequestProperty("Accept-Encoding", "identity")
        range?.let { c.setRequestProperty("Range", it) }
        if (body != null) {
            c.doOutput = true
            contentType?.let { c.setRequestProperty("Content-Type", it) }
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        val headers = c.headerFields
        c.disconnect()
        return Resp(code, bytes, headers)
    }

    private fun multipart(name: String, content: ByteArray): Pair<ByteArray, String> {
        val b = "----archivos" + System.nanoTime()
        val out = ByteArrayOutputStream()
        out.write("--$b\r\nContent-Disposition: form-data; name=\"f\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
        out.write(content)
        out.write("\r\n--$b--\r\n".toByteArray())
        return out.toByteArray() to "multipart/form-data; boundary=$b"
    }

    fun run(port: Int, code: String, root: File, setReadOnly: (Boolean) -> Unit): List<String> {
        val base = "http://127.0.0.1:$port"
        val fails = ArrayList<String>()
        val data = ByteArray(5000) { (it % 251).toByte() }
        File(root, "hola.txt").writeBytes(data)

        if (!call("$base/").text.contains("Código")) fails += "no mostró la página de entrada"
        if (call("$base/dl?p=hola.txt").code != 401) fails += "dejó descargar sin código"

        val bad = call("$base/login", "POST", body = "code=000000".toByteArray(), contentType = "application/x-www-form-urlencoded")
        if (bad.header("Set-Cookie") != null) fails += "aceptó un código incorrecto"

        val ok = call("$base/login", "POST", body = "code=$code".toByteArray(), contentType = "application/x-www-form-urlencoded")
        val cookie = ok.header("Set-Cookie")?.substringBefore(';')
        if (ok.code != 303 || cookie == null) { fails += "no aceptó el código correcto (${ok.code})"; return fails }

        if (!call("$base/b", cookie = cookie).text.contains("hola.txt")) fails += "no listó los archivos"
        if (!call("$base/dl?p=hola.txt", cookie = cookie).body.contentEquals(data)) fails += "la descarga no coincide"

        val part = call("$base/raw?p=hola.txt", cookie = cookie, range = "bytes=10-19")
        if (part.code != 206 || !part.body.contentEquals(data.copyOfRange(10, 20))) {
            fails += "no respondió por partes (video): código ${part.code}, ${part.body.size} bytes, ${part.header("Content-Encoding") ?: "sin compresión"}"
        }

        if (call("$base/dl?p=../../../etc/hosts", cookie = cookie).code !in listOf(403, 404)) fails += "permitió salirse de la carpeta"

        val up = "contenido subido ñ".toByteArray()
        val (body, ct) = multipart("subido.txt", up)
        val u = call("$base/up?ajax=1&p=", "POST", cookie, body, ct)
        if (u.code != 200 || !File(root, "subido.txt").readBytes().contentEquals(up)) fails += "no se pudo subir (${u.code}: ${u.text})"

        setReadOnly(true)
        val (body2, ct2) = multipart("bloqueado.txt", up)
        if (call("$base/up?ajax=1&p=", "POST", cookie, body2, ct2).code != 403 || File(root, "bloqueado.txt").exists()) fails += "subió en modo solo lectura"
        setReadOnly(false)
        // Justo después del rechazo, por la misma conexión: debe responder normal.
        if (call("$base/b", cookie = cookie).code != 200) fails += "la conexión quedó dañada tras un rechazo"

        val zip = call("$base/zip?p=", cookie = cookie)
        val names = try {
            java.util.zip.ZipInputStream(zip.body.inputStream()).use { z ->
                generateSequence { z.nextEntry }.map { it.name }.toList()
            }
        } catch (e: Exception) { listOf("error: ${e.message}") }
        if (zip.code != 200 || names.none { it.endsWith("hola.txt") }) {
            fails += "no generó el ZIP de la carpeta: código ${zip.code}, ${zip.body.size} bytes, contenido $names"
        }
        return fails
    }
}
