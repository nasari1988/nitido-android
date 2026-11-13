package com.limpio.app

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Servidor web local para pasar archivos entre el teléfono y una computadora en la misma red Wi‑Fi.
 * - Entrar exige un código de 6 dígitos; tras 5 intentos fallidos se bloquea 1 minuto.
 * - Nunca sale de [root] (se rechazan rutas como "../../").
 * - Respeta las carpetas protegidas del sistema y el modo de solo lectura.
 * No usa nada de Android: se prueba de punta a punta en cada compilación.
 */
class TransferServer(
    host: String?,
    port: Int,
    private val root: File,
    val code: String,
    private val readOnly: () -> Boolean,
    private val isHidden: (String) -> Boolean = { false },
    private val isProtected: (String) -> Boolean = { false },
    private val thumbnail: (File) -> ByteArray? = { null },
    private val onChange: (List<String>) -> Unit = {},
    private val onEvent: (String) -> Unit = {},
) : NanoHTTPD(host, port) {

    @Volatile var lastActivity = System.currentTimeMillis()
        private set
    private val tokens = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var failures = 0
    @Volatile private var blockedUntil = 0L
    private val rootPath: String = root.canonicalPath

    private class Status(private val code: Int, private val text: String) : Response.IStatus {
        override fun getDescription() = "$code $text"
        override fun getRequestStatus() = code
    }
    private val SEE_OTHER = Status(303, "See Other")
    private val RANGE_BAD = Status(416, "Range Not Satisfiable")

    companion object {
        fun newCode(): String = (SecureRandom().nextInt(900_000) + 100_000).toString()

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun size(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
            b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
            else -> "$b B"
        }

        fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "heic" -> "image/heic"; "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"; "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"; "flac" -> "audio/flac"; "pdf" -> "application/pdf"; "txt", "srt" -> "text/plain; charset=utf-8"
            "html", "htm" -> "text/plain; charset=utf-8" // nunca se ejecuta HTML subido
            "zip" -> "application/zip"; "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    // ---------- Rutas ----------

    /** Convierte la ruta relativa pedida en un archivo dentro de [root], o null si intenta salirse. */
    fun resolve(rel: String?): File? {
        val clean = (rel ?: "").replace('\\', '/').trim('/')
        val f = (if (clean.isEmpty()) root else File(root, clean)).canonicalFile
        val p = f.path
        if (p != rootPath && !p.startsWith("$rootPath/")) return null
        if (isHidden(f.absolutePath)) return null
        return f
    }

    private fun relOf(f: File) = f.canonicalPath.removePrefix(rootPath).trim('/')

    /** Sin compresión: en la red local no ahorra nada y rompe las respuestas por partes (video). */
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        lastActivity = System.currentTimeMillis()
        return try {
            route(session)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun authed(s: IHTTPSession) = s.cookies.read("arch_t")?.let { it in tokens } == true

    private fun param(s: IHTTPSession, name: String) = s.parameters[name]?.firstOrNull()

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri
        if (uri == "/login" && s.method == Method.POST) return login(s)
        if (!authed(s)) {
            return if (uri == "/" || uri == "/b") html(loginPage(null)) else newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Falta el código")
        }
        return when (uri) {
            "/" -> redirect("/b")
            "/b" -> browse(s)
            "/raw" -> file(s, attachment = false)
            "/dl" -> file(s, attachment = true)
            "/zip" -> zip(s)
            "/t" -> thumb(s)
            "/up" -> if (s.method == Method.POST) upload(s) else notFound()
            "/mkdir" -> if (s.method == Method.POST) mkdir(s) else notFound()
            "/del" -> if (s.method == Method.POST) delete(s) else notFound()
            "/salir" -> redirect("/").also { r -> s.cookies.read("arch_t")?.let { tokens.remove(it) }; r.addHeader("Set-Cookie", "arch_t=; Path=/; Max-Age=0") }
            else -> notFound()
        }
    }

    private fun html(body: String) = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No encontrado")
    private fun forbidden(msg: String) = newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, msg)
    private fun redirect(to: String) = newFixedLengthResponse(SEE_OTHER, MIME_PLAINTEXT, "").apply { addHeader("Location", to) }

    private fun login(s: IHTTPSession): Response {
        s.parseBody(HashMap())
        val now = System.currentTimeMillis()
        if (now < blockedUntil) return html(loginPage("Demasiados intentos. Espera un minuto."))
        if (param(s, "code")?.trim() != code) {
            failures++
            if (failures >= 5) { blockedUntil = now + 60_000; failures = 0 }
            return html(loginPage("Código incorrecto"))
        }
        failures = 0
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        tokens += token
        onEvent("Se conectó una computadora")
        return redirect("/b").apply { addHeader("Set-Cookie", "arch_t=$token; Path=/; HttpOnly; SameSite=Strict") }
    }

    private fun file(s: IHTTPSession, attachment: Boolean): Response {
        val f = resolve(param(s, "p")) ?: return forbidden("Ruta no permitida")
        if (!f.isFile) return notFound()
        val len = f.length()
        val range = s.headers["range"]
        val resp = if (range != null && range.startsWith("bytes=")) {
            val (a, b) = range.removePrefix("bytes=").split(",")[0].split("-").let { it[0] to it.getOrElse(1) { "" } }
            val start = a.toLongOrNull() ?: (len - (b.toLongOrNull() ?: 0))
            val end = if (a.isEmpty()) len - 1 else (b.toLongOrNull() ?: (len - 1)).coerceAtMost(len - 1)
            if (start < 0 || start > end || start >= len) {
                return newFixedLengthResponse(RANGE_BAD, MIME_PLAINTEXT, "").apply { addHeader("Content-Range", "bytes */$len") }
            }
            val input = FileInputStream(f).apply { skip(start) }
            newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime(f.name), input, end - start + 1).apply {
                addHeader("Content-Range", "bytes $start-$end/$len")
            }
        } else {
            if (attachment) onEvent("La computadora descargó ${f.name}")
            newFixedLengthResponse(Response.Status.OK, mime(f.name), FileInputStream(f), len)
        }
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader("X-Content-Type-Options", "nosniff")
        if (attachment) resp.addHeader("Content-Disposition", "attachment; filename*=UTF-8''${enc(f.name)}")
        return resp
    }

    private fun zip(s: IHTTPSession): Response {
        val dir = resolve(param(s, "p")) ?: return forbidden("Ruta no permitida")
        if (!dir.isDirectory) return notFound()
        val name = if (dir.canonicalPath == rootPath) "Almacenamiento" else dir.name
        val files = dir.walkTopDown().onEnter { !isHidden(it.absolutePath) }.filter { it.isFile }
            .map { it to "$name/" + it.relativeTo(dir).invariantSeparatorsPath }.toList()
        val pin = ZipFolderStream(files)
        onEvent("La computadora descargó la carpeta $name (ZIP)")
        return newChunkedResponse(Response.Status.OK, "application/zip", pin).apply {
            addHeader("Content-Disposition", "attachment; filename*=UTF-8''${enc("$name.zip")}")
        }
    }

    private fun thumb(s: IHTTPSession): Response {
        val f = resolve(param(s, "p")) ?: return forbidden("Ruta no permitida")
        val bytes = thumbnail(f) ?: return notFound()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", bytes.inputStream(), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=3600")
        }
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            f = File(dir, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }

    private fun upload(s: IHTTPSession): Response {
        if (readOnly()) return forbidden("Modo solo lectura: no se permite subir")
        val dir = resolve(param(s, "p")) ?: return forbidden("Ruta no permitida")
        if (!dir.isDirectory) return notFound()
        val files = HashMap<String, String>()
        s.parseBody(files)
        val names = s.parameters["f"].orEmpty()
        val keys = files.keys.filter { it == "f" || (it.startsWith("f") && it.drop(1).toIntOrNull() != null) }
            .sortedBy { if (it == "f") 1 else it.drop(1).toInt() }
        val saved = ArrayList<String>()
        keys.forEachIndexed { i, key ->
            val raw = names.getOrNull(i) ?: "archivo"
            val name = raw.substringAfterLast('/').substringAfterLast('\\').trim().ifEmpty { "archivo" }
            val dest = uniqueTarget(dir, name)
            val tmp = File(files.getValue(key))
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest); tmp.delete() }
            saved += dest.absolutePath
        }
        onChange(saved)
        onEvent("La computadora subió ${saved.size} archivo(s) a ${dir.name}")
        return if (param(s, "ajax") == "1") newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok ${saved.size}")
        else redirect("/b?p=" + enc(relOf(dir)))
    }

    private fun mkdir(s: IHTTPSession): Response {
        if (readOnly()) return forbidden("Modo solo lectura")
        s.parseBody(HashMap())
        val dir = resolve(param(s, "p")) ?: return forbidden("Ruta no permitida")
        val name = param(s, "name")?.trim()?.replace("/", "")?.replace("\\", "").orEmpty()
        if (name.isNotEmpty() && name != "." && name != "..") File(dir, name).mkdirs()
        return redirect("/b?p=" + enc(relOf(dir)))
    }

    private fun delete(s: IHTTPSession): Response {
        if (readOnly()) return forbidden("Modo solo lectura")
        s.parseBody(HashMap())
        val f = resolve(param(s, "p")) ?: return forbidden("Ruta no permitida")
        if (f.canonicalPath == rootPath || isProtected(f.absolutePath)) return forbidden("Carpeta del sistema: no se puede borrar")
        val parent = f.parentFile ?: root
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (ok) { onChange(listOf(f.absolutePath)); onEvent("La computadora borró ${f.name}") }
        return redirect("/b?p=" + enc(relOf(parent)))
    }

    // ---------- Páginas ----------

    private val css = """
        :root{color-scheme:light dark}
        body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:linear-gradient(135deg,#dff3ec,#e3e9ff 50%,#f3e6ff);min-height:100vh;color:#15181d}
        @media(prefers-color-scheme:dark){body{background:linear-gradient(135deg,#0d1f1a,#121a33 50%,#1d1230);color:#eef1f5}.card{background:#ffffff14!important;border-color:#ffffff24!important}.muted{color:#b3bcc8!important}a{color:#8cf0c8!important}}
        .wrap{max-width:1100px;margin:0 auto;padding:20px}
        .card{background:#ffffffb3;backdrop-filter:blur(18px);border:1px solid #ffffffcc;border-radius:20px;padding:16px;box-shadow:0 8px 24px #1b2a3a14}
        h1{font-size:22px;margin:0 0 4px}.muted{color:#5b6470;font-size:13px}
        a{color:#0b7a5f;text-decoration:none}
        .crumbs a{margin-right:4px}
        .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px;margin-top:14px}
        .it{display:flex;flex-direction:column;gap:6px;padding:10px;border-radius:16px;background:#ffffff80;border:1px solid #ffffffaa;overflow:hidden}
        @media(prefers-color-scheme:dark){.it{background:#ffffff10;border-color:#ffffff20}}
        .th{aspect-ratio:1;border-radius:12px;background:#0b7a5f1f;display:flex;align-items:center;justify-content:center;font-size:34px;overflow:hidden}
        .th img{width:100%;height:100%;object-fit:cover}
        .nm{font-size:13px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
        .ac{display:flex;gap:8px;font-size:12px;flex-wrap:wrap}
        button,.btn,input[type=submit]{background:#0b7a5f;color:#fff;border:0;border-radius:999px;padding:9px 16px;font:inherit;font-weight:600;cursor:pointer}
        input[type=text],input[type=password]{padding:10px 14px;border-radius:12px;border:1px solid #c9d1da;font:inherit;background:#fff8}
        .row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
        #drop{margin-top:12px;border:2px dashed #0b7a5f80;border-radius:16px;padding:18px;text-align:center}
        #drop.on{background:#0b7a5f1f}
        progress{width:100%}
        .del{background:none;color:#c62828;padding:0;font-size:12px;font-weight:600}
    """.trimIndent()

    private fun page(title: String, body: String) =
        "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>${esc(title)}</title><style>$css</style></head><body><div class=\"wrap\">$body</div></body></html>"

    private fun loginPage(error: String?) = page(
        "Archivos · Entrar",
        """<div class="card" style="max-width:420px;margin:12vh auto">
            <h1>Archivos</h1><p class="muted">Escribe el código que aparece en tu teléfono.</p>
            <form method="post" action="/login" class="row">
            <input type="password" name="code" inputmode="numeric" autocomplete="off" placeholder="Código de 6 dígitos" autofocus>
            <input type="submit" value="Entrar"></form>
            ${if (error != null) "<p style=\"color:#c62828;font-weight:600\">${esc(error)}</p>" else ""}
            </div>"""
    )

    private fun browse(s: IHTTPSession): Response {
        val dir = resolve(param(s, "p")) ?: return forbidden("Ruta no permitida")
        if (!dir.isDirectory) return notFound()
        val rel = relOf(dir)
        val ro = readOnly()
        val df = SimpleDateFormat("d MMM yyyy", Locale("es"))
        val crumbs = StringBuilder("<a href=\"/b\">Almacenamiento</a>")
        var acc = ""
        rel.split('/').filter { it.isNotEmpty() }.forEach { part ->
            acc = if (acc.isEmpty()) part else "$acc/$part"
            crumbs.append(" › <a href=\"/b?p=${enc(acc)}\">${esc(part)}</a>")
        }
        val entries = dir.listFiles().orEmpty()
            .filter { !isHidden(it.absolutePath) && !it.name.startsWith(".") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        val items = entries.joinToString("") { f ->
            val r = enc(relOf(f))
            val icon = when {
                f.isDirectory -> "📁"
                else -> when (FileTypes.kindOf(f.name)) {
                    FileKind.IMAGE -> "🖼"; FileKind.VIDEO -> "🎬"; FileKind.AUDIO -> "🎵"; FileKind.DOCUMENT -> "📄"
                    FileKind.ARCHIVE -> "🗜"; FileKind.APK -> "📦"; else -> "📎"
                }
            }
            val kind = if (f.isDirectory) null else FileTypes.kindOf(f.name)
            val th = if (kind == FileKind.IMAGE || kind == FileKind.VIDEO) "<img loading=\"lazy\" src=\"/t?p=$r\" alt=\"\" onerror=\"this.remove()\">" else ""
            val open = if (f.isDirectory) "/b?p=$r" else "/raw?p=$r"
            val sub = if (f.isDirectory) "${f.list()?.size ?: 0} elementos" else "${size(f.length())} · ${df.format(Date(f.lastModified()))}"
            val actions = buildString {
                if (f.isDirectory) append("<a href=\"/zip?p=$r\">ZIP</a>") else append("<a href=\"/dl?p=$r\">Descargar</a>")
                if (!ro && !isProtected(f.absolutePath)) append(
                    "<form method=\"post\" action=\"/del?p=$r\" onsubmit=\"return confirm('¿Borrar ${esc(f.name).replace("'", "\\'")} para siempre?')\" style=\"display:inline\"><button class=\"del\">Borrar</button></form>"
                )
            }
            """<div class="it"><a class="th" href="$open" ${if (f.isDirectory) "" else "target=\"_blank\""}>$icon$th</a>
               <a class="nm" href="$open" title="${esc(f.name)}" ${if (f.isDirectory) "" else "target=\"_blank\""}>${esc(f.name)}</a>
               <div class="muted" style="font-size:12px">$sub</div><div class="ac">$actions</div></div>"""
        }
        val tools = if (ro) "<p class=\"muted\">Modo solo lectura: puedes descargar, pero no subir ni borrar.</p>" else """
            <div id="drop">Arrastra archivos aquí o <label style="color:#0b7a5f;font-weight:600;cursor:pointer">elígelos<input id="pick" type="file" multiple hidden></label>
            <progress id="prog" value="0" max="100" hidden></progress><div id="msg" class="muted"></div></div>
            <form method="post" action="/mkdir?p=${enc(rel)}" class="row" style="margin-top:10px">
            <input type="text" name="name" placeholder="Nombre de la carpeta nueva"><input type="submit" value="Crear carpeta"></form>
            <script>
            const drop=document.getElementById('drop'),pick=document.getElementById('pick'),prog=document.getElementById('prog'),msg=document.getElementById('msg');
            function send(files){if(!files.length)return;const fd=new FormData();for(const f of files)fd.append('f',f,f.name);
              const x=new XMLHttpRequest();x.open('POST','/up?ajax=1&p=${enc(rel)}');prog.hidden=false;
              x.upload.onprogress=e=>{if(e.lengthComputable)prog.value=e.loaded/e.total*100;msg.textContent='Subiendo '+files.length+' archivo(s)…'};
              x.onload=()=>{msg.textContent=x.status==200?'Listo':'Error: '+x.responseText;setTimeout(()=>location.reload(),600)};x.send(fd);}
            pick.onchange=()=>send(pick.files);
            ['dragenter','dragover'].forEach(t=>drop.addEventListener(t,e=>{e.preventDefault();drop.classList.add('on')}));
            ['dragleave','drop'].forEach(t=>drop.addEventListener(t,e=>{e.preventDefault();drop.classList.remove('on')}));
            drop.addEventListener('drop',e=>send(e.dataTransfer.files));
            </script>"""
        return html(
            page(
                "Archivos · ${dir.name}",
                """<div class="card"><div class="row" style="justify-content:space-between"><div><h1>Archivos</h1>
                   <div class="crumbs muted">$crumbs</div></div><div class="row"><a class="btn" href="/zip?p=${enc(rel)}">Descargar carpeta (ZIP)</a>
                   <a href="/salir">Salir</a></div></div>$tools</div>
                   <div class="grid">$items</div>
                   ${if (entries.isEmpty()) "<p class=\"muted\">Carpeta vacía</p>" else ""}"""
            )
        )
    }
}

/**
 * Genera un ZIP "al vuelo" mientras se lee, sin hilos ni archivos temporales:
 * cada lectura comprime el siguiente pedazo del archivo actual.
 */
class ZipFolderStream(files: List<Pair<File, String>>) : InputStream() {
    private class Buffer : ByteArrayOutputStream() {
        fun take(): ByteArray = toByteArray().also { reset() }
    }
    private val buf = Buffer()
    private val zip = ZipOutputStream(buf)
    private val iter = files.iterator()
    private var current: FileInputStream? = null
    private var pending = ByteArray(0)
    private var pos = 0
    private var finished = false
    private val chunk = ByteArray(64 * 1024)

    private fun fill(): Boolean {
        while (pos >= pending.size) {
            if (finished) return false
            val c = current
            if (c == null) {
                if (iter.hasNext()) {
                    val (f, name) = iter.next()
                    zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                    current = try { FileInputStream(f) } catch (e: Exception) { zip.closeEntry(); null }
                } else {
                    zip.finish()
                    finished = true
                }
            } else {
                val n = c.read(chunk)
                if (n < 0) { c.close(); current = null; zip.closeEntry() } else zip.write(chunk, 0, n)
            }
            pending = buf.take()
            pos = 0
        }
        return true
    }

    override fun read(): Int = if (!fill()) -1 else pending[pos++].toInt() and 0xff

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, pending.size - pos)
        System.arraycopy(pending, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() {
        try { current?.close() } catch (_: Exception) {}
    }
}
