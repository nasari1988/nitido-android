package com.limpio.app

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.StatFs
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

data class Entry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val children: Int,
    val hidden: Boolean,
    val isProtected: Boolean,
) {
    val kind: FileKind? get() = if (isDir) null else FileTypes.kindOf(name)
    val isViewable: Boolean get() = kind == FileKind.IMAGE || kind == FileKind.VIDEO
}

enum class SortBy(val label: String) { NAME("Nombre"), SIZE("Tamaño"), DATE("Fecha") }
enum class OpType { MOVE, COPY }

enum class BrowseCategory(val label: String, val color: Long) {
    DOWNLOADS("Descargas", 0xFF1E88E5),
    IMAGES("Imágenes", 0xFF8E24AA),
    VIDEOS("Videos", 0xFFE53935),
    AUDIO("Audio", 0xFFF57C00),
    DOCUMENTS("Documentos", 0xFF43A047),
    APPS("Apps", 0xFF00897B),
    ARCHIVES("Comprimidos", 0xFF795548),
    RECENT("Recientes", 0xFF546E7A),
}

enum class DateFilter(val label: String, val days: Int) {
    ANY("Cualquier fecha", 0), DAY("Hoy", 1), WEEK("Últimos 7 días", 7), MONTH("Últimos 30 días", 30), YEAR("Último año", 365)
}

enum class SizeFilter(val label: String, val min: Long) {
    ANY("Cualquier tamaño", 0), MB10("Más de 10 MB", 10L shl 20), MB100("Más de 100 MB", 100L shl 20), GB1("Más de 1 GB", 1L shl 30)
}

/** Tipos que se pueden elegir en el filtro de búsqueda. */
val SEARCH_KINDS = listOf(
    BrowseCategory.IMAGES to FileKind.IMAGE,
    BrowseCategory.VIDEOS to FileKind.VIDEO,
    BrowseCategory.AUDIO to FileKind.AUDIO,
    BrowseCategory.DOCUMENTS to FileKind.DOCUMENT,
    BrowseCategory.APPS to FileKind.APK,
    BrowseCategory.ARCHIVES to FileKind.ARCHIVE,
)

sealed interface Screen {
    data object Home : Screen
    data class Folder(val path: String) : Screen
    data class Cat(val cat: BrowseCategory) : Screen
    data object Search : Screen
    data object Music : Screen
}

data class Volume(val label: String, val path: String, val total: Long, val free: Long, val primary: Boolean)

data class Details(val entry: Entry, val rows: List<Pair<String, String>>, val loading: Boolean)

fun entryOf(f: File): Entry? {
    val p = f.absolutePath
    if (Protection.isHiddenSystem(p) || !f.exists()) return null
    val isDir = f.isDirectory
    return Entry(
        path = p, name = f.name, isDir = isDir,
        size = if (isDir) 0 else f.length(),
        modified = f.lastModified(),
        children = if (isDir) f.list()?.size ?: 0 else 0,
        hidden = Protection.relative(p).split('/').any { it.startsWith(".") },
        isProtected = Protection.isProtected(p),
    )
}

/** Consultas rápidas a la base de medios de Android (no recorre carpetas). */
object MediaQueries {
    private val uri = MediaStore.Files.getContentUri("external")
    private val DOC_EXTS = listOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "ods", "odp", "rtf", "csv", "epub"
    )
    private val ARCHIVE_EXTS = listOf("zip", "rar", "7z", "tar", "gz", "tgz")
    private val APK_EXTS = listOf("apk", "apks", "xapk")

    @Suppress("DEPRECATION")
    private const val DATA = MediaStore.Files.FileColumns.DATA

    private fun extSelection(exts: List<String>) =
        "(" + exts.joinToString(" OR ") { "$DATA LIKE ?" } + ")" to exts.map { "%.$it" }

    private fun query(
        ctx: Context,
        selection: String,
        args: List<String>,
        sort: String = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        limit: Int = 0,
    ): List<Entry> {
        val out = ArrayList<Entry>()
        val qa = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
            if (limit > 0) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        try {
            ctx.contentResolver.query(uri, arrayOf(DATA), qa, null)?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    entryOf(File(path))?.let { out += it }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun category(ctx: Context, cat: BrowseCategory): List<Entry> {
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        return when (cat) {
            BrowseCategory.IMAGES -> query(ctx, "$type = ?", listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()))
            BrowseCategory.VIDEOS -> query(ctx, "$type = ?", listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()))
            BrowseCategory.AUDIO -> query(ctx, "$type = ?", listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString()))
            BrowseCategory.DOCUMENTS -> extSelection(DOC_EXTS).let { (s, a) -> query(ctx, s, a) }
            BrowseCategory.APPS -> extSelection(APK_EXTS).let { (s, a) -> query(ctx, s, a) }
            BrowseCategory.ARCHIVES -> extSelection(ARCHIVE_EXTS).let { (s, a) -> query(ctx, s, a) }
            BrowseCategory.RECENT -> recent(ctx, 300)
            BrowseCategory.DOWNLOADS -> emptyList()
        }
    }

    fun recent(ctx: Context, limit: Int): List<Entry> {
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val (docSel, docArgs) = extSelection(DOC_EXTS)
        return query(
            ctx,
            "($type IN (1, 2, 3) OR $docSel) AND $DATA NOT LIKE ?",
            docArgs + "%/.%",
            limit = limit,
        )
    }

    fun search(ctx: Context, text: String): List<Entry> {
        val name = MediaStore.Files.FileColumns.DISPLAY_NAME
        return query(ctx, "$name LIKE ?", listOf("%$text%"), limit = 500)
    }
}

class BrowseViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("explorar", Context.MODE_PRIVATE)
    val root = Protection.rootPath

    var stack by mutableStateOf<List<Screen>>(listOf(Screen.Home))
        private set
    val screen: Screen get() = stack.last()

    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var showHidden by mutableStateOf(prefs.getBoolean("hidden", false))
        private set
    var sortBy by mutableStateOf(SortBy.entries.firstOrNull { it.name == prefs.getString("sort", null) } ?: SortBy.NAME)
        private set
    var gridKeys by mutableStateOf(prefs.getStringSet("grid", setOf("IMAGES", "VIDEOS"))!!.toSet())
        private set
    var favorites by mutableStateOf(prefs.getStringSet("favs", emptySet())!!.toSet())
        private set

    var selection by mutableStateOf<Set<String>>(emptySet())
        private set
    var pending by mutableStateOf<Pair<OpType, List<String>>?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    var query by mutableStateOf("")
        private set
    var searchKind by mutableStateOf<BrowseCategory?>(null)
        private set
    var searchDate by mutableStateOf(DateFilter.ANY)
        private set
    var searchSize by mutableStateOf(SizeFilter.ANY)
        private set
    var recentSearches by mutableStateOf(
        prefs.getString("recentSearches", "")!!.split('\n').filter { it.isNotBlank() }
    )
        private set

    val hasSearchFilters: Boolean
        get() = searchKind != null || searchDate != DateFilter.ANY || searchSize != SizeFilter.ANY

    fun setSearchFilters(
        kind: BrowseCategory? = searchKind,
        date: DateFilter = searchDate,
        size: SizeFilter = searchSize,
    ) {
        searchKind = kind
        searchDate = date
        searchSize = size
        load()
    }

    fun rememberSearch() {
        val q = query.trim()
        if (q.length < 2) return
        recentSearches = (listOf(q) + recentSearches.filterNot { it.equals(q, ignoreCase = true) }).take(8)
        prefs.edit().putString("recentSearches", recentSearches.joinToString("\n")).apply()
    }

    fun clearRecentSearches() {
        recentSearches = emptyList()
        prefs.edit().remove("recentSearches").apply()
    }
    var folderSizes by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    var sizing by mutableStateOf(false)
        private set
    var details by mutableStateOf<Details?>(null)
    var zipPrompt by mutableStateOf<Entry?>(null)
    var viewer by mutableStateOf<List<Entry>?>(null)
        private set
    var viewerStart by mutableStateOf(0)
        private set

    // Inicio
    var catStats by mutableStateOf<Map<BrowseCategory, Pair<Int, Long>>>(emptyMap())
        private set
    var recent by mutableStateOf<List<Entry>>(emptyList())
        private set
    var volumes by mutableStateOf<List<Volume>>(emptyList())
        private set
    var favEntries by mutableStateOf<List<Entry>>(emptyList())
        private set

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var sizeJob: Job? = null

    private val ctx: Context get() = getApplication()

    // ---------- Navegación ----------

    fun navigate(s: Screen) {
        stack = stack + s
        onScreenChanged()
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        onScreenChanged()
        return true
    }

    fun goHome() {
        stack = listOf(Screen.Home)
        onScreenChanged()
    }

    fun openFolder(path: String) = navigate(Screen.Folder(path))

    fun openCategory(c: BrowseCategory) = when (c) {
        BrowseCategory.DOWNLOADS -> openFolder("$root/Download")
        BrowseCategory.AUDIO -> navigate(Screen.Music)
        else -> navigate(Screen.Cat(c))
    }

    private fun onScreenChanged() {
        selection = emptySet()
        sizeJob?.cancel()
        sizing = false
        folderSizes = emptyMap()
        entries = emptyList()
        load()
    }

    val isGrid: Boolean get() = gridKey(screen) in gridKeys

    private fun gridKey(s: Screen) = when (s) {
        is Screen.Folder -> "FOLDER"
        is Screen.Cat -> s.cat.name
        Screen.Search -> "SEARCH"
        Screen.Home, Screen.Music -> ""
    }

    fun toggleGrid() {
        val k = gridKey(screen)
        gridKeys = if (k in gridKeys) gridKeys - k else gridKeys + k
        prefs.edit().putStringSet("grid", gridKeys).apply()
    }

    fun toggleHidden() {
        showHidden = !showHidden
        prefs.edit().putBoolean("hidden", showHidden).apply()
        load()
    }

    fun sort(by: SortBy) {
        sortBy = by
        prefs.edit().putString("sort", by.name).apply()
        entries = sorted(entries)
    }

    // ---------- Carga ----------

    fun load() {
        loadJob?.cancel()
        val s = screen
        if (s is Screen.Home) {
            loadHome()
            return
        }
        if (s is Screen.Music) return
        loading = true
        loadJob = viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                when (s) {
                    is Screen.Folder -> File(s.path).listFiles().orEmpty().mapNotNull { entryOf(it) }
                    is Screen.Cat -> MediaQueries.category(ctx, s.cat)
                    Screen.Search -> searchResults()
                    Screen.Home, Screen.Music -> emptyList()
                }.filter { showHidden || !it.hidden }
            }
            if (s == screen) {
                entries = if (s is Screen.Cat && s.cat == BrowseCategory.RECENT) list else sorted(list)
                loading = false
            }
        }
    }

    private fun searchResults(): List<Entry> {
        val q = query.trim()
        if (q.length < 2 && !hasSearchFilters) return emptyList()
        val base = when {
            q.length >= 2 -> MediaQueries.search(ctx, q)
            searchKind != null -> MediaQueries.category(ctx, searchKind!!)
            else -> MediaQueries.search(ctx, "")
        }
        val kind = SEARCH_KINDS.firstOrNull { it.first == searchKind }?.second
        val since = if (searchDate.days > 0) System.currentTimeMillis() - searchDate.days * 86_400_000L else 0L
        return base.filter { e ->
            (kind == null || e.kind == kind) &&
                (since == 0L || e.modified >= since) &&
                (searchSize.min == 0L || (!e.isDir && e.size >= searchSize.min))
        }
    }

    private fun sorted(list: List<Entry>): List<Entry> {
        fun size(e: Entry) = if (e.isDir) folderSizes[e.path] ?: -1 else e.size
        val cmp: Comparator<Entry> = when (sortBy) {
            SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.SIZE -> compareByDescending { size(it) }
            SortBy.DATE -> compareByDescending { it.modified }
        }
        return list.sortedWith(compareByDescending<Entry> { it.isDir }.then(cmp))
    }

    fun loadHome() {
        viewModelScope.launch {
            volumes = withContext(Dispatchers.IO) { readVolumes() }
            favEntries = withContext(Dispatchers.IO) { favorites.mapNotNull { entryOf(File(it)) }.sortedBy { !it.isDir } }
            recent = withContext(Dispatchers.IO) { MediaQueries.recent(ctx, 20).filter { showHidden || !it.hidden } }
            val stats = HashMap<BrowseCategory, Pair<Int, Long>>()
            for (c in BrowseCategory.entries) {
                if (c == BrowseCategory.RECENT) continue
                val list = withContext(Dispatchers.IO) {
                    if (c == BrowseCategory.DOWNLOADS) File("$root/Download").listFiles().orEmpty().mapNotNull { entryOf(it) }
                    else MediaQueries.category(ctx, c)
                }.filter { showHidden || !it.hidden }
                stats[c] = list.size to list.sumOf { it.size }
                catStats = HashMap(stats)
            }
        }
    }

    private fun readVolumes(): List<Volume> {
        val out = ArrayList<Volume>()
        fun add(label: String, path: String, primary: Boolean) {
            try {
                val st = StatFs(path)
                out += Volume(label, path, st.totalBytes, st.availableBytes, primary)
            } catch (_: Exception) {
            }
        }
        add("Almacenamiento interno", root, true)
        // Tarjetas SD y memorias USB: se deducen de las carpetas de la app en cada volumen.
        ctx.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { d ->
            val p = d.absolutePath.substringBefore("/Android/data")
            if (File(p).canRead()) add("Tarjeta SD", p, false)
        }
        return out
    }

    fun setSearch(text: String) {
        query = text
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            load()
        }
    }

    // ---------- Abrir ----------

    fun openEntry(e: Entry) {
        if (screen == Screen.Search) rememberSearch()
        when {
            e.isDir -> openFolder(e.path)
            e.kind == FileKind.AUDIO -> {
                val songs = entries.filter { it.kind == FileKind.AUDIO }.map { it.path }
                Music.playFiles(ctx, songs, songs.indexOf(e.path).coerceAtLeast(0))
                Music.showNowPlaying = true
            }
            e.kind == FileKind.VIDEO -> {
                val vids = entries.filter { it.kind == FileKind.VIDEO }.map { it.path }
                Playback.playVideo(vids, vids.indexOf(e.path).coerceAtLeast(0))
            }
            e.isViewable -> {
                val list = entries.filter { it.isViewable }
                viewerStart = list.indexOfFirst { it.path == e.path }.coerceAtLeast(0)
                viewer = list
            }
            FileTypes.ext(e.name) == "zip" -> zipPrompt = e
            else -> FileOps.open(ctx, e.path)
        }
    }

    fun openViewer(list: List<Entry>, start: Entry) {
        val l = list.filter { it.isViewable }
        viewerStart = l.indexOfFirst { it.path == start.path }.coerceAtLeast(0)
        viewer = l
    }

    fun closeViewer() {
        viewer = null
    }

    // ---------- Selección ----------

    fun toggleSelect(e: Entry) {
        if (e.isProtected) {
            message = "\"${e.name}\" es una carpeta del sistema: puedes abrirla y manejar lo que tiene dentro, " +
                "pero no borrarla, moverla ni renombrarla"
            return
        }
        selection = if (e.path in selection) selection - e.path else selection + e.path
    }

    fun selectAll() {
        selection = entries.filterNot { it.isProtected }.map { it.path }.toSet()
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun selectedEntries(): List<Entry> = entries.filter { it.path in selection }

    // ---------- Acciones ----------

    private fun afterChange(msg: String) {
        message = msg
        busy = null
        selection = emptySet()
        StorageChanges.bump()
        load()
    }

    fun startOp(type: OpType) {
        pending = type to selection.toList()
        selection = emptySet()
    }

    fun cancelOp() {
        pending = null
    }

    val currentFolder: String? get() = (screen as? Screen.Folder)?.path

    fun pasteHere() {
        val (type, paths) = pending ?: return
        val dir = currentFolder ?: return
        if (paths.any { FileOps.isInside(dir, it) }) {
            message = "No puedes poner una carpeta dentro de sí misma"
            return
        }
        busy = if (type == OpType.MOVE) "Moviendo…" else "Copiando…"
        viewModelScope.launch {
            var ok = 0
            val changed = ArrayList<String>()
            withContext(Dispatchers.IO) {
                for (p in paths) {
                    val src = File(p)
                    if (type == OpType.MOVE && src.parent == dir) { ok++; continue }
                    val res = if (type == OpType.MOVE) FileOps.move(src, File(dir)) else FileOps.copy(src, File(dir))
                    if (res != null) {
                        ok++
                        changed += res.absolutePath
                        if (type == OpType.MOVE) changed += p
                    }
                }
                FileOps.rescan(ctx, changed)
            }
            pending = null
            afterChange("${if (type == OpType.MOVE) "Moviste" else "Copiaste"} $ok de ${paths.size} elementos")
        }
    }

    fun delete(paths: Collection<String>) {
        val targets = paths.filterNot { Protection.isProtected(it) }
        if (targets.isEmpty()) return
        busy = "Eliminando…"
        viewModelScope.launch {
            var ok = 0
            withContext(Dispatchers.IO) {
                for (p in targets) if (FileOps.deletePermanently(File(p))) ok++
                FileOps.rescan(ctx, targets)
            }
            removeFromViewer(targets.toSet())
            afterChange("Eliminaste permanentemente $ok elementos")
        }
    }

    private fun removeFromViewer(paths: Set<String>) {
        val v = viewer ?: return
        val left = v.filterNot { it.path in paths }
        viewer = left.ifEmpty { null }
    }

    fun rename(path: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || name.contains('/')) { message = "Nombre no válido"; return }
        if (Protection.isProtected(path)) return
        val src = File(path)
        val dest = File(src.parentFile, name)
        if (dest.exists()) { message = "Ya existe \"$name\""; return }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                src.renameTo(dest).also { if (it) FileOps.rescan(ctx, listOf(path, dest.absolutePath)) }
            }
            if (ok && path in favorites) {
                saveFavorites(favorites - path + dest.absolutePath)
            }
            afterChange(if (ok) "Renombrado" else "No se pudo renombrar")
        }
    }

    fun newFolder(name: String) {
        val dir = currentFolder ?: return
        val n = name.trim()
        if (n.isEmpty() || n.contains('/')) { message = "Nombre no válido"; return }
        val f = File(dir, n)
        if (f.exists()) { message = "Ya existe \"$n\""; return }
        afterChange(if (f.mkdirs()) "Carpeta creada" else "No se pudo crear la carpeta")
    }

    private fun saveFavorites(f: Set<String>) {
        favorites = f
        prefs.edit().putStringSet("favs", f).apply()
    }

    fun toggleFavorite(path: String) {
        val adding = path !in favorites
        saveFavorites(if (adding) favorites + path else favorites - path)
        message = if (adding) "Agregado a favoritos" else "Quitado de favoritos"
        selection = emptySet()
        if (screen == Screen.Home) loadHome()
    }

    /** Comprime la selección en un ZIP dentro de la carpeta actual (o en Descargas). */
    fun compress() {
        val sel = selectedEntries()
        if (sel.isEmpty()) return
        val dir = File(currentFolder ?: "$root/Download")
        val base = if (sel.size == 1) sel.first().name.substringBeforeLast('.', sel.first().name) else "Archivos"
        busy = "Comprimiendo…"
        viewModelScope.launch {
            val dest = withContext(Dispatchers.IO) {
                val target = FileOps.uniqueTarget(dir, "$base.zip")
                if (ZipOps.zip(sel.map { File(it.path) }, target)) target.also { FileOps.rescan(ctx, listOf(it.absolutePath)) } else null
            }
            afterChange(if (dest != null) "Se creó ${dest.name} en ${Protection.relative(dir.absolutePath)}" else "No se pudo comprimir")
        }
    }

    fun extract(e: Entry) {
        zipPrompt = null
        val parent = File(e.path).parentFile ?: return
        busy = "Extrayendo…"
        viewModelScope.launch {
            val (count, dest) = withContext(Dispatchers.IO) {
                val target = FileOps.uniqueTarget(parent, e.name.substringBeforeLast('.'))
                val n = ZipOps.unzip(File(e.path), target)
                if (n >= 0) FileOps.rescan(ctx, listOf(target.absolutePath)) else target.deleteRecursively()
                n to target
            }
            afterChange(if (count >= 0) "Se extrajeron $count archivos en ${dest.name}" else "No se pudo extraer (¿ZIP dañado o con contraseña?)")
        }
    }

    /** Calcula el tamaño real de cada carpeta de la vista actual (para ver qué ocupa más). */
    fun computeFolderSizes() {
        sizeJob?.cancel()
        val dirs = entries.filter { it.isDir }
        if (dirs.isEmpty()) return
        sizing = true
        sizeJob = viewModelScope.launch {
            for (d in dirs) {
                val size = withContext(Dispatchers.IO) {
                    var total = 0L
                    File(d.path).walkTopDown()
                        .onEnter { !Protection.isHiddenSystem(it.absolutePath) }
                        .forEach { if (it.isFile) total += it.length() }
                    total
                }
                ensureActive()
                folderSizes = folderSizes + (d.path to size)
            }
            sizing = false
            if (sortBy == SortBy.SIZE) entries = sorted(entries)
        }
    }

    fun showDetails(e: Entry) {
        details = Details(e, baseRows(e), loading = true)
        viewModelScope.launch {
            val extra = withContext(Dispatchers.IO) { extraRows(e) }
            if (details?.entry?.path == e.path) details = Details(e, baseRows(e) + extra, loading = false)
        }
    }

    private fun baseRows(e: Entry): List<Pair<String, String>> {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return listOfNotNull(
            "Nombre" to e.name,
            "Ubicación" to ("Almacenamiento/" + Protection.relative(e.path).substringBeforeLast('/', "")),
            "Modificado" to df.format(Date(e.modified)),
            if (e.isDir) null else "Tamaño" to formatSize(ctx, e.size),
            if (e.isDir) null else "Tipo" to FileTypes.mimeOf(e.name),
            if (e.hidden) "Oculto" to "Sí" else null,
            if (e.isProtected) "Protegido" to "Carpeta del sistema" else null,
        )
    }

    private fun extraRows(e: Entry): List<Pair<String, String>> = try {
        when {
            e.isDir -> {
                var size = 0L; var files = 0; var dirs = 0
                File(e.path).walkTopDown().onEnter { !Protection.isHiddenSystem(it.absolutePath) }.forEach {
                    if (it.isFile) { size += it.length(); files++ } else dirs++
                }
                listOf("Tamaño" to formatSize(ctx, size), "Contiene" to "$files archivos, ${dirs - 1} carpetas")
            }
            e.kind == FileKind.IMAGE -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(e.path, o)
                if (o.outWidth > 0) listOf(
                    "Resolución" to "${o.outWidth} × ${o.outHeight} (${"%.1f".format(o.outWidth * o.outHeight / 1_000_000.0)} MP)"
                ) else emptyList()
            }
            e.kind == FileKind.VIDEO || e.kind == FileKind.AUDIO -> {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(e.path)
                    val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    val s = ms / 1000
                    listOfNotNull(
                        "Duración" to if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60),
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.let { w ->
                            "Resolución" to "$w × ${mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)}"
                        },
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { "Título" to it },
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { "Artista" to it },
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let {
                            "Calidad" to "${it / 1000} kbps"
                        },
                    )
                } finally {
                    mmr.release()
                }
            }
            else -> emptyList()
        }
    } catch (ex: Exception) {
        emptyList()
    }
}
