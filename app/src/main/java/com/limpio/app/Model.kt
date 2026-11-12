package com.limpio.app

/**
 * Categorías de limpieza. Las marcadas como [safe] entran en la "Limpieza rápida"
 * de un toque; las demás se revisan a mano antes de borrar.
 */
enum class Category(val title: String, val description: String, val safe: Boolean) {
    CACHE(
        "Caché y temporales",
        "Carpetas de caché, miniaturas y archivos temporales. Las apps los vuelven a crear si los necesitan.",
        true
    ),
    LEFTOVERS(
        "Restos de apps desinstaladas",
        "Carpetas que dejaron apps que ya no tienes instaladas.",
        true
    ),
    EMPTY(
        "Carpetas y archivos vacíos",
        "Carpetas sin contenido y archivos de 0 bytes.",
        true
    ),
    APKS(
        "Instaladores APK",
        "Archivos .apk. Vienen marcados los de apps que ya tienes instaladas.",
        true
    ),
    WHATSAPP_SENT(
        "Enviados de WhatsApp",
        "Copias de fotos, videos y documentos que tú enviaste. El chat no se borra.",
        false
    ),
    OLD_DOWNLOADS(
        "Descargas antiguas",
        "Archivos en Descargas con más de 90 días.",
        false
    ),
    LARGE(
        "Archivos grandes",
        "Archivos de más de 100 MB.",
        false
    ),
}

data class CleanItem(
    val path: String,
    val size: Long,
    val isDir: Boolean,
    val lastModified: Long,
    /** Si viene marcado por defecto (y se borra en la limpieza rápida si la categoría es segura). */
    val preselected: Boolean,
    val note: String? = null,
    /** Dato extra (p. ej. "Parecido 97% · 0:15"); la etiqueta final se arma en [note]. */
    val detail: String? = null,
    /** Resolución (ancho × alto) para elegir la de mejor calidad; 0 si no aplica. */
    val pixels: Long = 0,
    /** Grupo de duplicados; -1 si no aplica. */
    val group: Int = -1,
) {
    val name: String get() = path.substringAfterLast('/')
}

data class ScanResult(
    val items: Map<Category, List<CleanItem>>,
    val filesScanned: Int,
    val durationMs: Long,
)

enum class FileKind { IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, OTHER }

object FileTypes {
    private val map: Map<String, FileKind> = buildMap {
        listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "dng")
            .forEach { put(it, FileKind.IMAGE) }
        listOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts", "wmv", "flv")
            .forEach { put(it, FileKind.VIDEO) }
        listOf("mp3", "m4a", "flac", "ogg", "wav", "aac", "opus", "wma", "amr", "mid", "midi")
            .forEach { put(it, FileKind.AUDIO) }
        listOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "ods", "odp",
            "rtf", "csv", "epub", "md"
        ).forEach { put(it, FileKind.DOCUMENT) }
        listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
            .forEach { put(it, FileKind.ARCHIVE) }
        put("apk", FileKind.APK)
        put("apks", FileKind.APK)
        put("xapk", FileKind.APK)
    }

    fun ext(name: String): String = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun kindOf(name: String): FileKind = map[ext(name)] ?: FileKind.OTHER

    fun mimeOf(name: String): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(name)) ?: "*/*"
}

/**
 * Reglas para no tocar nada sensible del sistema.
 * - Android/data, Android/obb y .android_secure ni siquiera se muestran.
 * - Las carpetas estándar (DCIM, Download, Music…), Android/, Android/media y las
 *   carpetas de cada app dentro de Android/media no se pueden borrar, mover ni renombrar.
 * - Los archivos .nomedia tampoco (controlan qué aparece en la galería).
 */
object Protection {
    val rootPath: String = android.os.Environment.getExternalStorageDirectory().absolutePath

    private val standardTop = setOf(
        "Android", "DCIM", "Download", "Documents", "Pictures", "Music", "Movies",
        "Alarms", "Notifications", "Podcasts", "Ringtones", "Audiobooks", "Recordings"
    )
    private val hiddenSystem = listOf(
        "$rootPath/Android/data", "$rootPath/Android/obb", "$rootPath/.android_secure"
    )

    fun isHiddenSystem(path: String): Boolean =
        hiddenSystem.any { path == it || path.startsWith("$it/") }

    fun isProtected(path: String): Boolean {
        if (path == rootPath) return true
        val name = path.substringAfterLast('/')
        if (name == ".nomedia") return true
        if (isHiddenSystem(path)) return true
        val parent = path.substringBeforeLast('/')
        if (parent == rootPath && name in standardTop) return true
        val android = "$rootPath/Android/"
        if (path.startsWith(android)) {
            // Android/media (0) y Android/media/<app> (1)
            if (path.removePrefix(android).count { it == '/' } <= 1) return true
        }
        return false
    }

    fun relative(path: String): String = path.removePrefix("$rootPath/").let { if (it == rootPath) "" else it }
}
