package com.limpio.app

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DupViewModel(app: Application) : AndroidViewModel(app) {

    var options by mutableStateOf(DupPrefs.load(app))
        private set
    var progress by mutableStateOf<Phase.Scanning?>(null)
        private set
    var results by mutableStateOf<Map<DupSection, List<CleanItem>>?>(null)
        private set
    var section by mutableStateOf(DupSection.EXACT)
    var selection by mutableStateOf<Map<DupSection, Set<String>>>(emptyMap())
        private set
    var deleting by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    private var job: Job? = null

    fun update(o: DupOptions) {
        options = o
        DupPrefs.save(getApplication(), o)
    }

    fun addFolder(path: String) {
        if (path !in options.folders) update(options.copy(folders = options.folders + path, wholeDevice = false))
    }

    fun removeFolder(path: String) = update(options.copy(folders = options.folders - path))

    fun start() {
        if (progress != null || !options.canSearch) return
        val scanner = DupScanner(getApplication(), options)
        progress = Phase.Scanning(0, "Buscando archivos")
        job = viewModelScope.launch {
            val ticker = launch {
                while (isActive) {
                    progress = scanner.progress.snapshot(scanner.filesFound)
                    delay(150)
                }
            }
            try {
                val r = scanner.run()
                results = r
                selection = r.mapValues { (_, v) -> v.filter { it.preselected }.map { it.path }.toSet() }
                section = r.entries.firstOrNull { it.value.isNotEmpty() }?.key ?: r.keys.firstOrNull() ?: DupSection.EXACT
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Error al buscar: ${e.message}"
            } finally {
                ticker.cancel()
                progress = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
        progress = null
    }

    fun newSearch() {
        results = null
        selection = emptyMap()
    }

    fun toggle(s: DupSection, path: String) {
        val cur = selection[s].orEmpty()
        selection = selection + (s to if (path in cur) cur - path else cur + path)
    }

    fun setAll(s: DupSection, on: Boolean) {
        val all = results?.get(s).orEmpty().map { it.path }.toSet()
        selection = selection + (s to if (on) all else emptySet())
    }

    /** "Conservar este": lo deja como el bueno del grupo y marca las demás copias. */
    fun keep(s: DupSection, item: CleanItem) {
        val r = results ?: return
        val list = r[s].orEmpty()
        val group = list.filter { it.group == item.group }
        val relabeled = Keep.label(group, item.path, markOthers = true, exact = s == DupSection.EXACT)
        val newList = list.filter { it.group != item.group }.toMutableList()
        // Mantener el grupo en su lugar.
        val index = list.indexOfFirst { it.group == item.group }
        newList.addAll(index.coerceAtMost(newList.size), relabeled)
        results = r + (s to newList)
        val others = group.map { it.path }.filter { it != item.path }
        val cur = selection[s].orEmpty() - group.map { it.path }.toSet()
        selection = selection + (s to cur + others)
    }

    /** Selección rápida: aplica una regla a TODOS los grupos de la sección y marca las demás copias. */
    fun applyRuleAll(s: DupSection, rule: KeepRule) {
        val r = results ?: return
        val list = Keep.applyRule(r[s].orEmpty(), rule, markOthers = true, exact = s == DupSection.EXACT)
        results = r + (s to list)
        selection = selection + (s to list.filter { it.preselected }.map { it.path }.toSet())
    }

    /** Grupos en los que se marcaron TODAS las copias (se perdería el archivo por completo). */
    fun fullySelectedGroups(s: DupSection): Int {
        val sel = selection[s].orEmpty()
        return results?.get(s).orEmpty().groupBy { it.group }.values.count { g -> g.all { it.path in sel } }
    }

    fun delete(s: DupSection) {
        val items = results?.get(s).orEmpty().filter { it.path in selection[s].orEmpty() }
        if (items.isEmpty() || deleting) return
        deleting = true
        viewModelScope.launch {
            var freed = 0L
            val deleted = HashSet<String>()
            withContext(Dispatchers.IO) {
                for (i in items) if (FileOps.deletePermanently(File(i.path))) {
                    freed += i.size
                    deleted += i.path
                }
                FileOps.rescan(getApplication(), deleted)
            }
            prune { it.path !in deleted }
            deleting = false
            StorageChanges.bump()
            val failed = items.size - deleted.size
            message = "Eliminaste permanentemente ${formatSize(getApplication(), freed)}" +
                if (failed > 0) " · $failed no se pudieron borrar" else ""
        }
    }

    /** Quita lo que ya no existe (borrado aquí o desde otra app) y los grupos que quedan solos. */
    fun pruneMissing() {
        if (results == null || deleting) return
        viewModelScope.launch {
            val all = results?.values?.flatten().orEmpty().map { it.path }
            val missing = withContext(Dispatchers.IO) { all.filterNot { File(it).exists() }.toSet() }
            if (missing.isNotEmpty()) prune { it.path !in missing }
        }
    }

    private fun prune(keepIf: (CleanItem) -> Boolean) {
        val r = results ?: return
        val newR = r.mapValues { (_, list) ->
            list.filter(keepIf).groupBy { it.group }.values.filter { it.size > 1 }.flatten()
        }
        results = newR
        selection = selection.mapValues { (s, sel) ->
            val valid = newR[s].orEmpty().map { it.path }.toSet()
            sel.filter { it in valid }.toSet()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DupTab(vm: DupViewModel, onExit: () -> Unit = {}) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    LaunchedEffect(StorageChanges.version) { vm.pruneMissing() }
    val results = vm.results
    BackHandler(enabled = vm.progress == null) { if (results != null) vm.newSearch() else onExit() }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Duplicados") },
                navigationIcon = {
                    if (vm.progress == null) {
                        IconButton(onClick = { if (results != null) vm.newSearch() else onExit() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                        }
                    }
                },
                actions = {
                    if (results != null && vm.progress == null) {
                        IconButton(onClick = { vm.newSearch() }) { Icon(Icons.Filled.Tune, "Opciones") }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val p = vm.progress
            when {
                p != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ProgressBlock(p, "${p.count} archivos revisados")
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { vm.cancel() }) { Text("Cancelar") }
                }
                results != null -> DupResults(vm, results)
                else -> DupSetup(vm)
            }
            if (vm.deleting) {
                AlertDialog(
                    onDismissRequest = {}, confirmButton = {},
                    title = { Text("Eliminando…") },
                    text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                )
            }
        }
    }
}

@Composable
private fun DupResults(vm: DupViewModel, results: Map<DupSection, List<CleanItem>>) {
    val ctx = LocalContext.current
    val s = vm.section
    val items = results[s].orEmpty()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            results.forEach { (sec, list) ->
                val groups = list.map { it.group }.distinct().size
                FilterChip(
                    selected = sec == s,
                    onClick = { vm.section = sec },
                    label = { Text("${sec.title} ($groups)") }
                )
            }
        }
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.ContentCopy, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("No se encontraron ${s.title.lowercase()}.")
            }
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Selección rápida:", style = MaterialTheme.typography.labelMedium)
            listOf(
                KeepRule.BEST to "Conservar mejor calidad",
                KeepRule.ORIGINAL to "Conservar original",
                KeepRule.OLDEST to "Conservar más antiguo",
                KeepRule.NEWEST to "Conservar más reciente",
            ).forEach { (rule, label) ->
                AssistChip(onClick = { vm.applyRuleAll(s, rule) }, label = { Text(label) })
            }
            AssistChip(onClick = { vm.setAll(s, false) }, label = { Text("Desmarcar todo") })
        }
        val groups = items.groupBy { it.group }.values
        val recoverable = groups.sumOf { g -> g.filterNot { it.note?.startsWith(Keep.KEPT_TAG) == true }.sumOf { it.size } }
        ReviewList(
            header = "${groups.size} grupos · hasta ${formatSize(ctx, recoverable)} recuperables. " +
                when (s) {
                    DupSection.EXACT -> "Las copias vienen marcadas; la que dice \"Se conserva\" no."
                    else -> "Nada viene marcado: usa la selección rápida o toca \"Conservar este\" en el bueno."
                },
            items = items,
            selected = vm.selection[s].orEmpty(),
            onToggle = { vm.toggle(s, it) },
            onSetAll = { vm.setAll(s, it) },
            grouped = true,
            destructive = true,
            actionLabel = { n, size -> "Eliminar $n · ${formatSize(ctx, size)}" },
            confirmTitle = { n -> "¿Eliminar $n archivos?" },
            confirmText = { size ->
                val full = vm.fullySelectedGroups(s)
                (if (full > 0) "⚠ En $full grupo(s) marcaste TODAS las copias: ese archivo desaparecerá por completo.\n\n" else "") +
                    "Liberarás ${formatSize(ctx, size)}. Se eliminarán de forma permanente: no pasan por " +
                    "ninguna papelera y no se pueden recuperar."
            },
            onConfirm = { vm.delete(s) },
            onKeep = { vm.keep(s, it) },
            groupTitle = { g ->
                val n = g.first().group + 1
                if (s == DupSection.EXACT) "Grupo $n · ${g.size} copias de ${formatSize(ctx, g.first().size)}"
                else "Grupo $n · ${g.size} ${if (s == DupSection.VIDEOS) "videos" else "fotos"} parecid${if (s == DupSection.VIDEOS) "os" else "as"}"
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DupSetup(vm: DupViewModel) {
    val o = vm.options
    var picker by remember { mutableStateOf(false) }
    val root = Protection.rootPath
    val shortcuts = listOf(
        "Cámara" to "$root/DCIM",
        "Imágenes" to "$root/Pictures",
        "Descargas" to "$root/Download",
        "Películas" to "$root/Movies",
        "WhatsApp" to "$root/Android/media/com.whatsapp/WhatsApp/Media",
        "Telegram" to "$root/Telegram",
    ).filter { File(it.second).isDirectory }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "La búsqueda solo corre cuando tú la pides. Elige dónde y qué buscar.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        // ---- Dónde
        item {
            SetupCard("Dónde buscar") {
                RadioRow("Todo el teléfono", o.wholeDevice) { vm.update(o.copy(wholeDevice = true)) }
                RadioRow("Solo en carpetas específicas", !o.wholeDevice) { vm.update(o.copy(wholeDevice = false)) }
                if (!o.wholeDevice) {
                    Spacer(Modifier.height(8.dp))
                    o.folders.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                Protection.relative(f).ifEmpty { "Almacenamiento" },
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            IconButton(onClick = { vm.removeFolder(f) }) { Icon(Icons.Filled.Close, "Quitar") }
                        }
                    }
                    if (o.folders.isEmpty()) {
                        Text(
                            "Agrega al menos una carpeta.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (shortcuts.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Atajos", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            shortcuts.forEach { (label, path) ->
                                FilterChip(
                                    selected = path in o.folders,
                                    onClick = { if (path in o.folders) vm.removeFolder(path) else vm.addFolder(path) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                    TextButton(onClick = { picker = true }) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Elegir otra carpeta")
                    }
                }
            }
        }
        // ---- Qué
        item {
            SetupCard("Qué buscar") {
                SwitchRow("Archivos idénticos", "Copias exactas, byte por byte", o.exact) {
                    vm.update(o.copy(exact = it))
                }
                if (o.exact) {
                    FlowRow(
                        Modifier.padding(start = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DupKind.entries.forEach { k ->
                            FilterChip(
                                selected = k in o.kinds,
                                onClick = { vm.update(o.copy(kinds = if (k in o.kinds) o.kinds - k else o.kinds + k)) },
                                label = { Text(k.label) }
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SwitchRow("Videos parecidos", "Reenviados, recodificados u otra resolución", o.videos) {
                    vm.update(o.copy(videos = it))
                }
                SwitchRow("Fotos parecidas", "Ráfagas, capturas repetidas, fotos reenviadas", o.photos) {
                    vm.update(o.copy(photos = it))
                }
                if (o.videos || o.photos) {
                    Text(
                        "La primera vez tarda más; después solo revisa lo nuevo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        // ---- Filtros
        item {
            SetupCard("Filtros") {
                Text("Ignorar archivos menores a", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Ninguno" to 0L, "100 KB" to 100L * 1024, "1 MB" to 1024L * 1024, "10 MB" to 10L * 1024 * 1024)
                        .forEach { (label, size) ->
                            FilterChip(
                                selected = o.minSize == size,
                                onClick = { vm.update(o.copy(minSize = size)) },
                                label = { Text(label) }
                            )
                        }
                }
                SwitchRow("Incluir ocultos", "Archivos y carpetas que empiezan con punto", o.includeHidden) {
                    vm.update(o.copy(includeHidden = it))
                }
            }
        }
        // ---- Cuál conservar
        item {
            SetupCard("Cuál conservar de cada grupo") {
                KeepRule.entries.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.update(o.copy(keep = r)) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = o.keep == r, onClick = { vm.update(o.copy(keep = r)) })
                        Column {
                            Text(r.label)
                            Text(r.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    "En los resultados puedes cambiarlo en cada grupo con \"Conservar este\".",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        item {
            Button(
                onClick = { vm.start() },
                enabled = o.canSearch,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Filled.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("Buscar duplicados")
            }
        }
    }

    if (picker) {
        FolderPickerDialog(onDismiss = { picker = false }) {
            picker = false
            vm.addFolder(it)
        }
    }
}

@Composable
private fun SetupCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Navegador de carpetas para elegir dónde buscar. No muestra carpetas del sistema. */
@Composable
fun FolderPickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val root = Protection.rootPath
    var dir by remember { mutableStateOf(root) }
    val subdirs = remember(dir) {
        File(dir).listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") && !Protection.isHiddenSystem(it.absolutePath) }
            .sortedBy { it.name.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Elegir carpeta")
                Text(
                    "Almacenamiento/" + Protection.relative(dir),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                if (dir != root) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable { dir = File(dir).parent ?: root }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            Spacer(Modifier.width(12.dp))
                            Text("Subir")
                        }
                    }
                }
                items(subdirs, key = { it.absolutePath }) { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable { dir = d.absolutePath }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (subdirs.isEmpty()) {
                    item { Text("Sin subcarpetas", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(dir) }, enabled = dir != root) { Text("Elegir esta") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
