package com.limpio.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Tipos de archivo que se pueden incluir en la búsqueda de idénticos. */
enum class DupKind(val label: String, val kinds: Set<FileKind>) {
    PHOTOS("Fotos", setOf(FileKind.IMAGE)),
    VIDEOS("Videos", setOf(FileKind.VIDEO)),
    MUSIC("Música", setOf(FileKind.AUDIO)),
    DOCS("Documentos", setOf(FileKind.DOCUMENT)),
    OTHER("Otros", setOf(FileKind.ARCHIVE, FileKind.APK, FileKind.OTHER)),
}

enum class KeepRule(val label: String, val description: String) {
    BEST("La de mejor calidad", "Mayor resolución y, si empatan, el archivo más pesado. En copias idénticas, el original"),
    ORIGINAL("El original", "Prefiere la copia de tu cámara o tus carpetas sobre la de WhatsApp, Telegram o Descargas"),
    OLDEST("El más antiguo", "Conserva la copia con la fecha más vieja"),
    NEWEST("El más reciente", "Conserva la copia con la fecha más nueva"),
}

enum class DupSection(val title: String) {
    EXACT("Idénticos"),
    VIDEOS("Videos parecidos"),
    PHOTOS("Fotos parecidas"),
}

data class DupOptions(
    val wholeDevice: Boolean = true,
    val folders: List<String> = emptyList(),
    val exact: Boolean = true,
    val videos: Boolean = false,
    val photos: Boolean = false,
    val kinds: Set<DupKind> = DupKind.entries.toSet(),
    val minSize: Long = 100L * 1024,
    val includeHidden: Boolean = false,
    val keep: KeepRule = KeepRule.BEST,
) {
    val canSearch: Boolean
        get() = (exact && kinds.isNotEmpty() || videos || photos) && (wholeDevice || folders.isNotEmpty())
}

object Keep {
    /** Qué tan "original" parece una ruta: más alto = más probable que sea la buena. */
    fun originScore(path: String): Int {
        val p = path.lowercase()
        return when {
            p.contains("/dcim/camera/") -> 100
            p.contains("whatsapp") || p.contains("telegram") || p.contains("/sent/") -> 10
            p.contains("/dcim/") -> 90
            p.contains("/download/") || p.contains("/bluetooth/") -> 20
            p.contains("/pictures/") || p.contains("/movies/") ||
                p.contains("/music/") || p.contains("/documents/") -> 80
            else -> 50
        }
    }

    fun pick(group: List<CleanItem>, rule: KeepRule): String = when (rule) {
        KeepRule.OLDEST -> group.minBy { it.lastModified }
        KeepRule.NEWEST -> group.maxBy { it.lastModified }
        KeepRule.BEST -> group.sortedWith(
            compareByDescending<CleanItem> { it.pixels }
                .thenByDescending { it.size }
                .thenByDescending { originScore(it.path) }
                .thenBy { it.lastModified }
        ).first()
        KeepRule.ORIGINAL -> group.sortedWith(
            compareByDescending<CleanItem> { originScore(it.path) }.thenBy { it.lastModified }
        ).first()
    }.path

    /**
     * Arma las etiquetas de un grupo dejando [keepPath] como el que se conserva.
     * [markOthers]: si las demás copias vienen marcadas para borrar.
     */
    fun label(group: List<CleanItem>, keepPath: String, markOthers: Boolean, exact: Boolean): List<CleanItem> =
        group.map { item ->
            val kept = item.path == keepPath
            val tag = if (kept) KEPT_TAG else if (exact) "Copia" else null
            item.copy(
                preselected = !kept && markOthers,
                note = listOfNotNull(tag, item.detail).joinToString(" · ").ifEmpty { null },
            )
        }.sortedByDescending { it.path == keepPath }

    fun applyRule(items: List<CleanItem>, rule: KeepRule, markOthers: Boolean, exact: Boolean): List<CleanItem> =
        items.groupBy { it.group }.values.flatMap { g -> label(g, pick(g, rule), markOthers, exact) }

    const val KEPT_TAG = "Se conserva"
}

/** Busca duplicados solo cuando se pide, en todo el teléfono o en las carpetas elegidas. */
class DupScanner(private val context: Context, private val options: DupOptions) {
    val progress = Progress()
    @Volatile var filesFound = 0
        private set

    private fun roots(): List<File> {
        val root = Protection.rootPath
        if (options.wholeDevice) return listOf(File(root))
        // Sin repetir: si elegiste una carpeta y también una de sus subcarpetas, cuenta una vez.
        val sorted = options.folders.distinct().sortedBy { it.length }
        val chosen = ArrayList<String>()
        for (f in sorted) if (chosen.none { FileOps.isInside(f, it) }) chosen += f
        return chosen.map { File(it) }.filter { it.isDirectory }
    }

    private fun collect(): List<FileRec> {
        val out = ArrayList<FileRec>()
        val seen = HashSet<String>()
        for (r in roots()) {
            r.walkTopDown()
                .onEnter { d ->
                    !Protection.isHiddenSystem(d.absolutePath) &&
                        (options.includeHidden || d == r || !d.name.startsWith("."))
                }
                .filter { it.isFile }
                .forEach { f ->
                    if (!options.includeHidden && f.name.startsWith(".")) return@forEach
                    val size = f.length()
                    if (size < maxOf(options.minSize, DuplicateFinder.MIN_DUP_SIZE)) return@forEach
                    if (seen.add(f.absolutePath)) {
                        out += FileRec(f.absolutePath, size, f.lastModified())
                        filesFound = out.size
                    }
                }
        }
        return out
    }

    suspend fun run(): Map<DupSection, List<CleanItem>> = withContext(Dispatchers.IO) {
        progress.start("Buscando archivos")
        val all = collect()
        ensureActive()
        val result = linkedMapOf<DupSection, List<CleanItem>>()
        val inExact = HashSet<String>()

        if (options.exact) {
            val allowed = options.kinds.flatMap { it.kinds }.toSet()
            val candidates = all.filter { FileTypes.kindOf(it.path) in allowed }
            progress.start("Buscando archivos idénticos")
            val found = DuplicateFinder.find(
                candidates,
                onTotal = { progress.total.addAndGet(it) },
                onStep = { progress.done.incrementAndGet() },
            ).map { it.copy(detail = null) }
            result[DupSection.EXACT] = Keep.applyRule(found, options.keep, markOthers = true, exact = true)
            found.forEach { inExact += it.path }
        }
        ensureActive()
        val similar = SimilarMedia(context, progress)
        if (options.videos) {
            val videos = all.filter {
                it.path !in inExact && it.size >= 100 * 1024 && FileTypes.kindOf(it.path) == FileKind.VIDEO
            }
            result[DupSection.VIDEOS] = Keep.applyRule(
                similar.similarVideos(videos), options.keep, markOthers = false, exact = false
            )
        }
        ensureActive()
        if (options.photos) {
            val photos = all.filter { it.path !in inExact && FileTypes.kindOf(it.path) == FileKind.IMAGE }
            result[DupSection.PHOTOS] = Keep.applyRule(
                similar.similarPhotos(photos), options.keep, markOthers = false, exact = false
            )
        }
        result
    }
}

/** Guarda la última configuración de búsqueda. */
object DupPrefs {
    private const val NAME = "duplicados"

    fun load(ctx: Context): DupOptions {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val d = DupOptions()
        return DupOptions(
            wholeDevice = p.getBoolean("whole", d.wholeDevice),
            folders = p.getStringSet("folders", emptySet())!!.sorted(),
            exact = p.getBoolean("exact", d.exact),
            videos = p.getBoolean("videos", d.videos),
            photos = p.getBoolean("photos", d.photos),
            kinds = p.getStringSet("kinds", null)
                ?.mapNotNull { n -> DupKind.entries.firstOrNull { it.name == n } }?.toSet() ?: d.kinds,
            minSize = p.getLong("minSize", d.minSize),
            includeHidden = p.getBoolean("hidden", d.includeHidden),
            keep = KeepRule.entries.firstOrNull { it.name == p.getString("keepRule", null) } ?: d.keep,
        )
    }

    fun save(ctx: Context, o: DupOptions) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("whole", o.wholeDevice)
            .putStringSet("folders", o.folders.toSet())
            .putBoolean("exact", o.exact)
            .putBoolean("videos", o.videos)
            .putBoolean("photos", o.photos)
            .putStringSet("kinds", o.kinds.map { it.name }.toSet())
            .putLong("minSize", o.minSize)
            .putBoolean("hidden", o.includeHidden)
            .putString("keepRule", o.keep.name)
            .apply()
    }
}
