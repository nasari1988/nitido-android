package com.nitido.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One downloadable Stable Diffusion model. mode 0 = normal SD 1.5, 1 = InstructPix2Pix (edits photos). */
data class SdModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val mode: Int,
    val approxMb: Int,
)

/**
 * Models are downloaded once into the app's private storage.
 * Converted models live as assets of the GitHub release "modelo-<id>" in the app's repository
 * (produced by the "Convertir modelo" workflow); the original SD 1.5 comes from Hugging Face.
 */
object SdModel {
    private const val RELEASES = "https://github.com/nasari1988/nitido-android/releases/download/"
    private const val HF_SD15 = "https://huggingface.co/taobao-mnn/stable-diffusion-v1-5-mnn-gpu/resolve/main/"
    private const val COMPLETE = ".complete"

    val ALL = listOf(
        SdModelInfo("dreamshaper8", "DreamShaper 8", "Versátil: fotos, arte y dibujos", 0, 1250),
        SdModelInfo("realistic", "Realistic Vision 5.1", "Fotos realistas y personas", 0, 1250),
        SdModelInfo("anime", "Anime Mix", "Anime, ilustración y bocetos", 0, 1250),
        SdModelInfo("pix2pix", "Editor por instrucciones", "Edita tus fotos: \"make it gold\", \"add snow\"", 1, 1250),
        SdModelInfo("sd15", "SD 1.5 original", "El modelo base", 0, 1150),
    )

    private const val PREFS = "nitido"
    private const val KEY_CUSTOM = "custom_models"

    /** Built-in models plus the ones the user added by name. */
    fun all(ctx: Context): List<SdModelInfo> = ALL + customIds(ctx).map { id ->
        SdModelInfo(id, ModelCatalog.displayName(id), ModelCatalog.description(id) ?: "Personalizado", readMode(ctx, id), 1250)
    }

    fun byId(ctx: Context, id: String?): SdModelInfo {
        if (id.isNullOrEmpty()) return ALL.first()
        ALL.firstOrNull { it.id == id }?.let { return it }
        return SdModelInfo(id, ModelCatalog.displayName(id), ModelCatalog.description(id) ?: "Personalizado", readMode(ctx, id), 1250)
    }

    fun customIds(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM, "")!!
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** Same rules as the converter: lowercase letters, digits and dashes. */
    fun cleanId(name: String): String =
        name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it == '-') it else '-' }
            .joinToString("").trim('-')

    fun addCustom(ctx: Context, id: String) {
        if (id.isEmpty() || ALL.any { it.id == id }) return
        val list = (customIds(ctx) + id).distinct()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CUSTOM, list.joinToString(",")).apply()
    }

    fun removeCustom(ctx: Context, id: String) {
        val list = customIds(ctx).filter { it != id }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CUSTOM, list.joinToString(",")).apply()
    }

    /** Models converted with free image size (newer conversions) can make vertical and horizontal pictures. */
    fun isDynamic(ctx: Context, id: String): Boolean = readInfo(ctx, id, "dynamic") == "1"

    fun precision(ctx: Context, id: String): String? = readInfo(ctx, id, "precision")

    /** SDXL models: bigger and slower, native size 1024 px. */
    fun isSdxl(ctx: Context, id: String): Boolean = readInfo(ctx, id, "arch") == "sdxl"

    fun lora(ctx: Context, id: String): String? = readInfo(ctx, id, "lora")

    private fun readInfo(ctx: Context, id: String, key: String): String? = try {
        val f = File(dir(ctx, id), "info.txt")
        if (f.exists()) f.readLines().firstOrNull { it.startsWith("$key=") }?.substringAfter("=")?.trim() else null
    } catch (t: Throwable) { null }

    /** Converted models carry info.txt with "mode=0|1" (1 = edits photos by instruction). */
    fun readMode(ctx: Context, id: String): Int = try {
        val f = File(dir(ctx, id), "info.txt")
        if (f.exists()) f.readLines().firstOrNull { it.startsWith("mode=") }?.substringAfter("=")?.trim()?.toIntOrNull() ?: 0 else 0
    } catch (t: Throwable) { 0 }

    private val SD15_FILES = linkedMapOf(
        "text_encoder.mnn" to 250_000L,
        "text_encoder.mnn.weight" to 238_000_000L,
        "unet.mnn" to 1_010_000L,
        "unet.mnn.weight" to 864_000_000L,
        "vae_decoder.mnn" to 164_000L,
        "vae_decoder.mnn.weight" to 49_600_000L,
        "vocab.json" to 1_060_000L,
        "merges.txt" to 525_000L,
        "alphas.txt" to 7_000L,
    )

    fun dir(ctx: Context, id: String): File =
        (if (id == "sd15") File(ctx.filesDir, "sd15") else File(ctx.filesDir, "models/$id")).apply { mkdirs() }

    fun isReady(ctx: Context, id: String): Boolean {
        val d = dir(ctx, id)
        if (File(d, COMPLETE).exists()) return true
        // the original model was installed before completion markers existed
        return id == "sd15" && SD15_FILES.keys.all { File(d, it).let { f -> f.exists() && f.length() > 0 } }
    }

    fun delete(ctx: Context, id: String) { dir(ctx, id).deleteRecursively() }

    /** The original SD 1.5 has no VAE encoder of its own; the app ships one. */
    fun ensureEncoder(ctx: Context, id: String): Boolean {
        if (id != "sd15") return true
        val f = File(dir(ctx, id), "vae_encoder.mnn")
        return try {
            if (!f.exists() || f.length() == 0L) {
                ctx.assets.open("vae_encoder.mnn").use { input -> f.outputStream().use { input.copyTo(it) } }
            }
            true
        } catch (t: Throwable) { false }
    }

    /** Downloads every missing file of model [id], resuming partial ones. [onProgress] gets (done, total) bytes. */
    fun download(ctx: Context, id: String, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) {
        val d = dir(ctx, id)
        val base: String
        val files: Map<String, Long>
        if (id == "sd15") {
            base = HF_SD15
            files = SD15_FILES
        } else {
            base = "${RELEASES}modelo-$id/"
            val listFile = File(d, "files.txt")
            fetch(base + "files.txt", listFile, append = false, cancelled = cancelled) {}
            files = listFile.readLines().mapNotNull { line ->
                val p = line.trim().split(" ")
                if (p.size == 2) p[0] to (p[1].toLongOrNull() ?: 0L) else null
            }.toMap(LinkedHashMap())
            if (files.isEmpty()) throw IOException("lista de archivos vacía")
        }
        val total = files.values.sum()
        var doneBefore = 0L
        // pieces of a big file already joined on the phone (and unchanged) aren't downloaded again
        val joined = files.keys.filter { Regex(""".+\.split\d\d$""").matches(it) }
            .groupBy { it.substringBeforeLast(".split") }
            .filter { (base, parts) -> File(d, base).length() == parts.sumOf { files[it] ?: 0L } }
            .values.flatten().toSet()
        for ((name, size) in files) {
            val target = File(d, name)
            if (name in joined) { doneBefore += size; onProgress(doneBefore, total); continue }
            if (target.exists() && target.length() > 0 && (size <= 0 || id == "sd15" || target.length() == size)) {
                doneBefore += size; onProgress(doneBefore, total); continue
            }
            val part = File(d, "$name.part")
            var attempt = 0
            while (true) {
                if (cancelled()) throw InterruptedException()
                try {
                    fetch(base + name, part, append = true, cancelled = cancelled) { got -> onProgress(doneBefore + minOf(got, size), total) }
                    break
                } catch (e: IOException) {
                    attempt++
                    if (attempt >= 5) throw e
                    Thread.sleep(2000L * attempt)
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("No se pudo guardar $name")
            doneBefore += size
            onProgress(doneBefore, total)
        }
        joinSplitFiles(d)
        if (id == "sd15") ensureEncoder(ctx, id)
        File(d, COMPLETE).writeText("ok")
    }

    /** Files over 2 GB come in pieces "name.split00", "name.split01"…; put them back together. */
    private fun joinSplitFiles(d: File) {
        val pieces = d.listFiles()?.filter { Regex(""".+\.split\d\d$""").matches(it.name) } ?: return
        for ((name, parts) in pieces.groupBy { it.name.substringBeforeLast(".split") }) {
            val target = File(d, name)
            val tmp = File(d, "$name.joining")
            tmp.outputStream().use { out ->
                for (p in parts.sortedBy { it.name }) {
                    p.inputStream().use { it.copyTo(out, 1 shl 20) }
                    p.delete()   // free space as we go
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("No se pudo unir $name")
        }
    }

    private fun fetch(url: String, part: File, append: Boolean, cancelled: () -> Boolean, onBytes: (Long) -> Unit) {
        val have = if (append && part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 416) { onBytes(have); return }          // already complete
            val partial = code == HttpURLConnection.HTTP_PARTIAL
            if (code == 404) throw IOException("no encontrado. Revisa que el nombre sea igual al que usaste en Convertir modelo, que la conversión haya salido bien y que el repositorio sea público")
            if (code != HttpURLConnection.HTTP_OK && !partial) throw IOException("HTTP $code")
            var got = if (partial) have else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, partial).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw InterruptedException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (got - lastReport > 2_000_000) { onBytes(got); lastReport = got }
                    }
                }
            }
            onBytes(got)
        } finally {
            conn.disconnect()
        }
    }
}
