package com.nitido.app

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A model already converted and published in the GitHub repository (release "modelo-<id>"). */
data class RemoteModel(val id: String, val sizeBytes: Long, val date: String)

/** A model worth converting: where it comes from and the short name to give it. */
data class Recommendation(
    val id: String,
    val name: String,
    val description: String,
    val link: String,
    val precision: String,
)

/**
 * Lists every model the user can use: the ones on the phone, the ones already converted in
 * GitHub (found by looking at the repository's releases, so names are never lost), and
 * recommendations that haven't been converted yet.
 */
object ModelCatalog {
    const val REPO = "nasari1988/nitido-android"
    const val CONVERT_PAGE = "https://github.com/$REPO/actions/workflows/convert.yml"

    val RECOMMENDED = listOf(
        Recommendation("realistic-vision-6", "Realistic Vision 6.0",
            "Fotos realistas y personas. La versión más nueva del favorito para realismo.",
            "https://huggingface.co/SG161222/Realistic_Vision_V6.0_B1_noVAE", "16"),
        Recommendation("epicrealism", "epiCRealism",
            "Fotos muy naturales, luz y piel menos “de estudio”.",
            "https://huggingface.co/emilianJR/epiCRealism", "16"),
        Recommendation("cyberrealistic", "CyberRealistic 5",
            "Realista y muy fiel al prompt; bueno también para editar fotos.",
            "https://huggingface.co/cyberdelia/CyberRealistic/blob/main/CyberRealistic_V5_FP16.safetensors", "16"),
        Recommendation("realvisxl-5", "RealVisXL 5.0 · SDXL",
            "SDXL: el salto grande en realismo (1024 px). Lento: 1–3 min por imagen.",
            "https://huggingface.co/SG161222/RealVisXL_V5.0", "8"),
        Recommendation("realvisxl-5-lightning", "RealVisXL 5.0 Lightning · SDXL",
            "SDXL rápido: 5–8 pasos y fuerza del prompt 1.5–2.",
            "https://huggingface.co/SG161222/RealVisXL_V5.0_Lightning", "8"),
        Recommendation("juggernaut-xl-9", "Juggernaut XL 9 · SDXL",
            "SDXL todoterreno: retratos, paisajes y escenas de cine.",
            "https://huggingface.co/RunDiffusion/Juggernaut-XL-v9", "8"),
        Recommendation("dreamshaper8-16", "DreamShaper 8 (16 bits)",
            "Versátil: fotos, arte y dibujos, con más precisión.",
            "https://huggingface.co/Lykon/dreamshaper-8", "16"),
        Recommendation("dreamshaper-8-inpainting", "DreamShaper 8 Inpainting",
            "Para cambiar solo una zona pintada de la foto (inpainting). Rápido.",
            "https://huggingface.co/Lykon/dreamshaper-8-inpainting", "16"),
        Recommendation("sdxl-inpainting", "SDXL Inpainting",
            "Inpainting de alta calidad (SDXL). Más lento y pesado.",
            "https://huggingface.co/diffusers/stable-diffusion-xl-1.0-inpainting-0.1", "8"),
    )

    /** Friendly name for an id, when we know one. */
    fun displayName(id: String): String =
        SdModel.ALL.firstOrNull { it.id == id }?.name
            ?: RECOMMENDED.firstOrNull { it.id == id }?.name
            ?: id

    fun description(id: String): String? =
        SdModel.ALL.firstOrNull { it.id == id }?.description
            ?: RECOMMENDED.firstOrNull { it.id == id }?.description

    /** Converted models published in GitHub. Throws if there is no internet. */
    fun fetchRemote(): List<RemoteModel> {
        val conn = (URL("https://api.github.com/repos/$REPO/releases?per_page=100").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Nitido-app")
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub respondió ${conn.responseCode}")
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val outFmt = SimpleDateFormat("d MMM yyyy", Locale("es", "MX"))
            val list = mutableListOf<RemoteModel>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val tag = r.optString("tag_name")
                if (!tag.startsWith("modelo-")) continue
                val assets = r.optJSONArray("assets") ?: continue
                var size = 0L
                var hasList = false
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    size += a.optLong("size")
                    if (a.optString("name") == "files.txt") hasList = true
                }
                if (!hasList) continue   // conversion not finished / failed
                val date = try { outFmt.format(inFmt.parse(r.optString("published_at"))!!) } catch (t: Throwable) { "" }
                list += RemoteModel(tag.removePrefix("modelo-"), size, date)
            }
            return list.sortedBy { displayName(it.id).lowercase() }
        } finally {
            conn.disconnect()
        }
    }

    /** Models fully downloaded on this phone. */
    fun local(ctx: Context): List<String> {
        val ids = mutableListOf<String>()
        if (SdModel.isReady(ctx, "sd15")) ids += "sd15"
        File(ctx.filesDir, "models").listFiles()?.filter { it.isDirectory && File(it, ".complete").exists() && it.name != RestorePack.ID }
            ?.forEach { ids += it.name }
        return ids
    }

    fun sizeOnPhone(ctx: Context, id: String): Long =
        SdModel.dir(ctx, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun gb(bytes: Long) = String.format(Locale.US, "%.1f GB", bytes / 1e9)

    fun mb(bytes: Long) = if (bytes >= 1e9) gb(bytes) else String.format(Locale.US, "%d MB", (bytes / 1e6).toLong())
}
