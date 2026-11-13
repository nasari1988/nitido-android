package com.limpio.app

import java.io.File

/** Una canción de la biblioteca. */
data class Track(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val dateAdded: Long = 0,
) {
    val folder: String get() = path.substringBeforeLast('/')
}

/** Un grupo de la biblioteca (álbum, artista o carpeta). */
data class TrackGroup(val name: String, val subtitle: String, val tracks: List<Track>)

object MediaUtil {
    /** 75 000 → "1:15"; 3 723 000 → "1:02:03". */
    fun fmtTime(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private val SUB_EXTS = listOf("srt", "vtt", "ass", "ssa")

    /** Subtítulos con el mismo nombre que el video: "pelicula.mp4" → "pelicula.srt" o "pelicula.es.srt". */
    fun findSubtitle(video: File): File? {
        val base = video.nameWithoutExtension
        val dir = video.parentFile ?: return null
        SUB_EXTS.forEach { ext ->
            File(dir, "$base.$ext").takeIf { it.isFile }?.let { return it }
        }
        return dir.listFiles()?.firstOrNull { f ->
            f.isFile && f.name.startsWith("$base.") && f.extension.lowercase() in SUB_EXTS
        }
    }

    fun unknown(s: String?, fallback: String) = s?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: fallback

    fun albums(tracks: List<Track>): List<TrackGroup> =
        tracks.groupBy { it.album.lowercase() to it.artist.lowercase() }.values
            .map { g -> TrackGroup(g.first().album, "${g.first().artist} · ${g.size} canciones", g.sortedBy { it.title.lowercase() }) }
            .sortedBy { it.name.lowercase() }

    fun artists(tracks: List<Track>): List<TrackGroup> =
        tracks.groupBy { it.artist.lowercase() }.values
            .map { g ->
                val albums = g.map { it.album }.distinct().size
                TrackGroup(g.first().artist, "$albums álbumes · ${g.size} canciones", g.sortedBy { it.title.lowercase() })
            }
            .sortedBy { it.name.lowercase() }

    fun folders(tracks: List<Track>): List<TrackGroup> =
        tracks.groupBy { it.folder }.entries
            .map { (dir, g) -> TrackGroup(dir.substringAfterLast('/'), "${g.size} canciones", g.sortedBy { it.path.lowercase() }) }
            .sortedBy { it.name.lowercase() }
}
