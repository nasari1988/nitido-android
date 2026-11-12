package com.limpio.app

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Entry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val children: Int,
    val hidden: Boolean,
    val isProtected: Boolean,
)

enum class SortBy(val label: String) { NAME("Nombre"), SIZE("Tamaño"), DATE("Fecha") }
enum class OpType { MOVE, COPY }

class ExplorerViewModel(app: Application) : AndroidViewModel(app) {
    val root = Protection.rootPath

    var dir by mutableStateOf(root)
        private set
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var showHidden by mutableStateOf(false)
        private set
    var sortBy by mutableStateOf(SortBy.NAME)
        private set
    var selection by mutableStateOf<Set<String>>(emptySet())
        private set
    var pending by mutableStateOf<Pair<OpType, List<String>>?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    init { load() }

    fun open(path: String) {
        dir = path
        selection = emptySet()
        load()
    }

    fun up(): Boolean {
        if (dir == root) return false
        open(File(dir).parent ?: root)
        return true
    }

    fun toggleHidden() { showHidden = !showHidden; load() }
    fun sort(by: SortBy) { sortBy = by; entries = sorted(entries) }

    fun load() {
        val d = dir
        loading = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                File(d).listFiles().orEmpty().mapNotNull { f ->
                    val p = f.absolutePath
                    val hidden = f.name.startsWith(".")
                    if (Protection.isHiddenSystem(p)) return@mapNotNull null
                    if (hidden && !showHidden) return@mapNotNull null
                    val isDir = f.isDirectory
                    Entry(
                        path = p, name = f.name, isDir = isDir,
                        size = if (isDir) 0 else f.length(),
                        modified = f.lastModified(),
                        children = if (isDir) f.list()?.size ?: 0 else 0,
                        hidden = hidden,
                        isProtected = Protection.isProtected(p),
                    )
                }
            }
            if (d == dir) {
                entries = sorted(list)
                loading = false
            }
        }
    }

    private fun sorted(list: List<Entry>): List<Entry> {
        val cmp: Comparator<Entry> = when (sortBy) {
            SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.SIZE -> compareByDescending { it.size }
            SortBy.DATE -> compareByDescending { it.modified }
        }
        return list.sortedWith(compareByDescending<Entry> { it.isDir }.then(cmp))
    }

    // ---------- Selección ----------

    fun toggleSelect(e: Entry) {
        if (e.isProtected) {
            message = "\"${e.name}\" es del sistema y está protegido"
            return
        }
        selection = if (e.path in selection) selection - e.path else selection + e.path
    }

    fun selectAll() {
        selection = entries.filterNot { it.isProtected }.map { it.path }.toSet()
    }

    fun clearSelection() { selection = emptySet() }

    // ---------- Acciones ----------

    fun startOp(type: OpType) {
        pending = type to selection.toList()
        selection = emptySet()
    }

    fun cancelOp() { pending = null }

    fun pasteHere() {
        val (type, paths) = pending ?: return
        val target = File(dir)
        // No se puede mover una carpeta dentro de sí misma.
        if (paths.any { FileOps.isInside(dir, it) }) {
            message = "No puedes poner una carpeta dentro de sí misma"
            return
        }
        busy = true
        viewModelScope.launch {
            var ok = 0
            val changed = ArrayList<String>()
            withContext(Dispatchers.IO) {
                for (p in paths) {
                    val src = File(p)
                    if (type == OpType.MOVE && src.parent == dir) { ok++; continue }
                    val res = if (type == OpType.MOVE) FileOps.move(src, target) else FileOps.copy(src, target)
                    if (res != null) {
                        ok++
                        changed += res.absolutePath
                        if (type == OpType.MOVE) changed += p
                    }
                }
                FileOps.rescan(getApplication(), changed)
            }
            val verb = if (type == OpType.MOVE) "Moviste" else "Copiaste"
            message = "$verb $ok de ${paths.size} elementos"
            pending = null
            busy = false
            load()
        }
    }

    fun deleteSelected() {
        val paths = selection.filterNot { Protection.isProtected(it) }
        busy = true
        viewModelScope.launch {
            var ok = 0
            withContext(Dispatchers.IO) {
                for (p in paths) if (FileOps.deletePermanently(File(p))) ok++
                FileOps.rescan(getApplication(), paths)
            }
            message = "Eliminaste permanentemente $ok elementos"
            selection = emptySet()
            busy = false
            load()
        }
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
                src.renameTo(dest).also { if (it) FileOps.rescan(getApplication(), listOf(path, dest.absolutePath)) }
            }
            message = if (ok) "Renombrado" else "No se pudo renombrar"
            selection = emptySet()
            load()
        }
    }

    fun newFolder(name: String) {
        val n = name.trim()
        if (n.isEmpty() || n.contains('/')) { message = "Nombre no válido"; return }
        val f = File(dir, n)
        if (f.exists()) { message = "Ya existe \"$n\""; return }
        message = if (f.mkdirs()) "Carpeta creada" else "No se pudo crear la carpeta"
        load()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ExplorerTab(vm: ExplorerViewModel) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    LaunchedEffect(Unit) { vm.load() }

    val selecting = vm.selection.isNotEmpty()
    BackHandler(enabled = selecting || vm.dir != vm.root) {
        if (selecting) vm.clearSelection() else vm.up()
    }

    var sortMenu by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Entry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${vm.selection.size} seleccionados") },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Filled.Close, "Cancelar") }
                    },
                    actions = {
                        IconButton(onClick = { vm.selectAll() }) { Icon(Icons.Filled.SelectAll, "Seleccionar todo") }
                        if (vm.selection.size == 1) {
                            IconButton(onClick = {
                                renameTarget = vm.entries.firstOrNull { it.path == vm.selection.first() }
                            }) { Icon(Icons.Filled.Edit, "Renombrar") }
                        }
                        IconButton(onClick = { vm.startOp(OpType.COPY) }) { Icon(Icons.Filled.ContentCopy, "Copiar") }
                        IconButton(onClick = { vm.startOp(OpType.MOVE) }) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Mover")
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Eliminar") }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (vm.dir == vm.root) "Archivos" else File(vm.dir).name,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Almacenamiento/" + Protection.relative(vm.dir),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        if (vm.dir != vm.root) IconButton(onClick = { vm.up() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Subir")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.toggleHidden() }) {
                            Icon(
                                if (vm.showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                if (vm.showHidden) "Ocultar archivos ocultos" else "Mostrar archivos ocultos"
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, "Ordenar")
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortBy.entries.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text((if (vm.sortBy == s) "✓ " else "") + s.label) },
                                        onClick = { vm.sort(s); sortMenu = false }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { newFolderDialog = true }) {
                            Icon(Icons.Filled.CreateNewFolder, "Nueva carpeta")
                        }
                    }
                )
            }
        },
        bottomBar = {
            vm.pending?.let { (type, paths) ->
                Card(
                    Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            (if (type == OpType.MOVE) "Mover" else "Copiar") +
                                " ${paths.size} elementos: abre la carpeta de destino",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(Modifier.padding(top = 8.dp)) {
                            OutlinedButton(onClick = { vm.cancelOp() }) { Text("Cancelar") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { vm.pasteHere() }, modifier = Modifier.weight(1f)) {
                                Text(if (type == OpType.MOVE) "Mover aquí" else "Copiar aquí")
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (vm.loading && vm.entries.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (vm.entries.isEmpty()) {
                Text(
                    "Carpeta vacía",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.entries, key = { it.path }) { e ->
                    val selected = e.path in vm.selection
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .combinedClickable(
                                onClick = {
                                    when {
                                        selecting -> vm.toggleSelect(e)
                                        e.isDir -> vm.open(e.path)
                                        else -> FileOps.open(ctx, e.path)
                                    }
                                },
                                onLongClick = { vm.toggleSelect(e) }
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .alpha(if (e.hidden) 0.6f else 1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FileThumb(e.path, e.isDir, 48.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (e.isDir) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                (if (e.isDir) "${e.children} elementos" else formatSize(ctx, e.size)) +
                                    " · " + formatDate(e.modified),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (e.isProtected) {
                            Icon(
                                Icons.Filled.Lock, "Protegido",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 78.dp), thickness = 0.5.dp)
                }
            }
            if (vm.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    if (newFolderDialog) {
        NameDialog("Nueva carpeta", "", onDismiss = { newFolderDialog = false }) {
            newFolderDialog = false
            vm.newFolder(it)
        }
    }
    renameTarget?.let { e ->
        NameDialog("Renombrar", e.name, onDismiss = { renameTarget = null }) {
            renameTarget = null
            vm.rename(e.path, it)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar ${vm.selection.size} elementos?") },
            text = {
                Text(
                    "Se eliminarán de forma permanente, incluido todo lo que haya dentro de las " +
                        "carpetas. No pasan por la papelera y no se pueden recuperar."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteSelected() }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
