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
    DUPLICATES(
        "Archivos duplicados",
        "Copias idénticas byte por byte. De cada grupo se conserva la copia más antigua.",
        false
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
