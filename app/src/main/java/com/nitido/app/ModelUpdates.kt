package com.nitido.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds updates for the models on the phone: compares each one's info.txt and files.txt with the
 * ones published in GitHub (release "modelo-<id>"). Only a few kilobytes are read per model.
 */
object ModelUpdates {
    private const val RELEASES = "https://github.com/${ModelCatalog.REPO}/releases/download/"

    /** What changed and how much has to be downloaded. */
    data class Update(val reason: String, val bytes: Long, val full: Boolean)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("model_updates", Context.MODE_PRIVATE)

    fun lastCheck(ctx: Context): Long = prefs(ctx).getLong("last", 0L)

    /** Saved result of the last check for [id], or null when it was up to date. */
    fun saved(ctx: Context, id: String): Update? {
        val s = prefs(ctx).getString("u_$id", null) ?: return null
        val p = s.split("|")
        return if (p.size == 3) Update(p[0], p[1].toLongOrNull() ?: 0L, p[2] == "1") else null
    }

    fun clear(ctx: Context, id: String) { prefs(ctx).edit().remove("u_$id").apply() }

    private fun get(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "Nitido-app")
        }
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    } catch (t: Throwable) { null }

    private fun parseFiles(text: String): Map<String, Long> = text.lines().mapNotNull { l ->
        val p = l.trim().split(" ")
        if (p.size == 2) p[0] to (p[1].toLongOrNull() ?: 0L) else null
    }.toMap()

    private fun parseInfo(text: String): Map<String, String> = text.lines().mapNotNull { l ->
        val i = l.indexOf('='); if (i > 0) l.substring(0, i).trim() to l.substring(i + 1).trim() else null
    }.toMap()

    /** Compares one downloaded model with GitHub. Null when it is up to date (or GitHub can't be reached). */
    fun check(ctx: Context, id: String): Update? {
        val d = SdModel.dir(ctx, id)
        val remoteFiles = get("${RELEASES}modelo-$id/files.txt")?.let { parseFiles(it) } ?: return null
        val remoteInfo = get("${RELEASES}modelo-$id/info.txt")?.let { parseInfo(it) } ?: emptyMap()
        val localFiles = File(d, "files.txt").takeIf { it.exists() }?.let { parseFiles(it.readText()) } ?: emptyMap()
        val localInfo = File(d, "info.txt").takeIf { it.exists() }?.let { parseInfo(it.readText()) } ?: emptyMap()
        if (remoteFiles == localFiles && remoteInfo == localInfo) return null

        val changed = remoteFiles.filter { (name, size) -> localFiles[name] != size }
        val bytes = changed.values.sum()
        val reasons = mutableListOf<String>()
        if (remoteInfo["dynamic"] == "1" && localInfo["dynamic"] != "1") reasons += "ahora admite vertical, horizontal y tamaño grande"
        val rv = remoteInfo["version"]?.toIntOrNull() ?: 0
        val lv = localInfo["version"]?.toIntOrNull() ?: 0
        if (rv > lv) reasons += "convertidor versión $rv (tenías la ${if (lv == 0) 1 else lv})"
        val added = remoteFiles.keys - localFiles.keys
        if (id == RestorePack.ID && added.isNotEmpty()) reasons += "nuevo: " + added.joinToString(", ") { packName(it) }
        if (remoteInfo["source"] != null && localInfo["source"] != null && remoteInfo["source"] != localInfo["source"])
            reasons += "versión nueva del autor"
        if (reasons.isEmpty()) {
            if (changed.isEmpty()) return null
            reasons += "archivos actualizados"
        }
        return Update(reasons.joinToString(" · "), bytes, bytes > remoteFiles.values.sum() * 0.8)
    }

    /** Checks every downloaded model and the restoration pack; saves and returns the updates found. */
    fun checkAll(ctx: Context): Map<String, Update> {
        val ids = ModelCatalog.local(ctx).filter { it != "sd15" } + (if (RestorePack.isReady(ctx)) listOf(RestorePack.ID) else emptyList())
        val out = HashMap<String, Update>()
        val e = prefs(ctx).edit()
        for (id in ids) {
            val u = check(ctx, id)
            if (u != null) { out[id] = u; e.putString("u_$id", "${u.reason}|${u.bytes}|${if (u.full) 1 else 0}") } else e.remove("u_$id")
        }
        e.putLong("last", System.currentTimeMillis()).apply()
        return out
    }

    /**
     * Gets an update ready: removes the files that changed (and half-downloaded pieces) so the
     * normal download fetches only those again. Unchanged files stay.
     */
    fun prepare(ctx: Context, id: String) {
        val d = SdModel.dir(ctx, id)
        val remoteFiles = get("${RELEASES}modelo-$id/files.txt")?.let { parseFiles(it) } ?: return
        d.listFiles()?.forEach { f ->
            val n = f.name
            when {
                n.endsWith(".part") || n == ".complete" -> f.delete()
                n == "files.txt" || n == "info.txt" -> f.delete()
                remoteFiles.containsKey(n) -> if (remoteFiles[n] != f.length()) f.delete()
                // a file joined from pieces: keep it only if none of its pieces changed
                remoteFiles.keys.any { it.startsWith("$n.split") } -> {
                    val pieces = remoteFiles.filterKeys { it.startsWith("$n.split") }
                    if (pieces.values.sum() != f.length()) f.delete()
                }
                else -> if (n.endsWith(".mnn") || n.contains(".split")) f.delete()
            }
        }
        clear(ctx, id)
    }

    fun packName(file: String) = when (file) {
        RestorePack.DENOISE -> "Grano"
        RestorePack.DEBLUR -> "Desenfoque"
        RestorePack.FACES -> "Caras"
        RestorePack.LAMA -> "Rayones"
        RestorePack.COLOR -> "Colorear"
        else -> file
    }
}
