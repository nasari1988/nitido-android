package com.limpio.app

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/** A dónde va cada tipo de archivo. */
enum class OrgRule(val title: String, val target: String, val kind: FileKind) {
    MUSIC("Música", "Music", FileKind.AUDIO),
    VIDEO("Videos", "Movies", FileKind.VIDEO),
    IMAGES("Imágenes", "Pictures", FileKind.IMAGE),
    DOCS("Documentos", "Documents", FileKind.DOCUMENT),
    ARCHIVES("Comprimidos", "Documents/Comprimidos", FileKind.ARCHIVE),
    APKS("Instaladores", "Download/Instaladores", FileKind.APK),
}

class OrganizerViewModel(app: Application) : AndroidViewModel(app) {
    private val root = Protection.rootPath

    var scanning by mutableStateOf(false)
        private set
    var moving by mutableStateOf(false)
        private set
    var groups by mutableStateOf<Map<OrgRule, List<CleanItem>>?>(null)
        private set
    var selection by mutableStateOf<Map<OrgRule, Set<String>>>(emptyMap())
        private set
    var open by mutableStateOf<OrgRule?>(null)
    var byYear by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    /**
     * Busca archivos sueltos: en la raíz, Descargas y Bluetooth (vienen marcados), y archivos
     * del tipo equivocado dentro de Music/Movies/Pictures/Documents (sin marcar).
     * Nunca toca DCIM (cámara), Android/ (WhatsApp y apps) ni carpetas ocultas.
     */
    fun scan() {
        if (scanning || moving) return
        scanning = true
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                val out = HashMap<OrgRule, MutableList<CleanItem>>()
                fun consider(f: File, preselected: Boolean) {
                    if (f.name.startsWith(".")) return
                    val kind = FileTypes.kindOf(f.name)
                    val rule = OrgRule.entries.firstOrNull { it.kind == kind } ?: return
                    val target = "$root/${rule.target}"
                    if (FileOps.isInside(f.parent ?: "", target)) return
                    val from = Protection.relative(f.parent ?: "").ifEmpty { "Almacenamiento" }
                    out.getOrPut(rule) { ArrayList() } += CleanItem(
                        f.absolutePath, f.length(), false, f.lastModified(), preselected,
                        note = "Desde $from"
                    )
                }
                fun walk(dir: File, preselected: Boolean) {
                    dir.walkTopDown()
                        .onEnter { !it.name.startsWith(".") && !Protection.isHiddenSystem(it.absolutePath) }
                        .filter { it.isFile }
                        .forEach { consider(it, preselected) }
                }
                // Raíz: solo archivos sueltos.
                File(root).listFiles()?.filter { it.isFile }?.forEach { consider(it, true) }
                walk(File(root, "Download"), true)
                walk(File(root, "Bluetooth"), true)
                for (std in listOf("Music", "Movies", "Pictures", "Documents")) {
                    walk(File(root, std), false)
                }
                out.mapValues { (_, v) -> v.sortedByDescending { it.size } }
            }
            groups = found
            selection = found.mapValues { (_, v) -> v.filter { it.preselected }.map { it.path }.toSet() }
            scanning = false
        }
    }

    fun toggle(rule: OrgRule, path: String) {
        val cur = selection[rule].orEmpty()
        selection = selection + (rule to if (path in cur) cur - path else cur + path)
    }

    fun setAll(rule: OrgRule, on: Boolean) {
        selection = selection + (rule to if (on) groups?.get(rule).orEmpty().map { it.path }.toSet() else emptySet())
    }

    fun destinationFor(rule: OrgRule, item: CleanItem): String {
        val year = if (byYear) {
            "/" + Calendar.getInstance().apply { timeInMillis = item.lastModified }.get(Calendar.YEAR)
        } else ""
        return rule.target + year
    }

    fun move(rule: OrgRule) {
        val items = groups?.get(rule).orEmpty().filter { it.path in selection[rule].orEmpty() }
        if (items.isEmpty() || moving) return
        moving = true
        viewModelScope.launch {
            val changed = ArrayList<String>()
            val moved = HashSet<String>()
            withContext(Dispatchers.IO) {
                for (item in items) {
                    val dest = File(root, destinationFor(rule, item))
                    val result = FileOps.move(File(item.path), dest)
                    if (result != null) {
                        moved += item.path
                        changed += item.path
                        changed += result.absolutePath
                    }
                }
                FileOps.rescan(getApplication(), changed)
            }
            groups = groups?.mapValues { (_, v) -> v.filterNot { it.path in moved } }
            selection = selection.mapValues { (_, v) -> v - moved }
            moving = false
            val failed = items.size - moved.size
            message = "Moviste ${moved.size} archivos a ${rule.target}" +
                if (failed > 0) " · $failed no se pudieron mover" else ""
            if (groups?.get(rule).isNullOrEmpty()) open = null
            StorageChanges.bump()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizerTab(vm: OrganizerViewModel) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    LaunchedEffect(StorageChanges.version) { if (vm.groups == null || vm.open == null) vm.scan() }
    val open = vm.open
    BackHandler(enabled = open != null) { vm.open = null }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(open?.let { "${it.title} → ${it.target}" } ?: "Organizar") },
                navigationIcon = {
                    if (open != null) IconButton(onClick = { vm.open = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                },
                actions = {
                    if (open == null) IconButton(onClick = { vm.scan() }) {
                        Icon(Icons.Filled.Refresh, "Volver a buscar")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (open != null) {
                val items = vm.groups?.get(open).orEmpty().map {
                    it.copy(note = "${it.note} → ${vm.destinationFor(open, it)}")
                }
                ReviewList(
                    header = "Los archivos se mueven (no se copian), así que no ocupan espacio extra. " +
                        "Si ya existe uno con el mismo nombre, se renombra con (1), (2)…",
                    items = items,
                    selected = vm.selection[open].orEmpty(),
                    onToggle = { vm.toggle(open, it) },
                    onSetAll = { vm.setAll(open, it) },
                    grouped = false,
                    destructive = false,
                    actionLabel = { n, _ -> "Mover $n a ${open.target}" },
                    confirmTitle = { n -> "¿Mover $n archivos?" },
                    confirmText = { s ->
                        "Se moverán ${formatSize(ctx, s)} a ${open.target}" +
                            (if (vm.byYear) ", en subcarpetas por año." else ".")
                    },
                    onConfirm = { vm.move(open) },
                )
            } else {
                OrganizerHome(vm)
            }
            if (vm.moving) {
                AlertDialog(
                    onDismissRequest = {}, confirmButton = {},
                    title = { Text("Moviendo…") },
                    text = { CircularProgressIndicator() }
                )
            }
        }
    }
}

@Composable
private fun OrganizerHome(vm: OrganizerViewModel) {
    val ctx = LocalContext.current
    val groups = vm.groups
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Lleva cada archivo suelto a su carpeta. Busca en la raíz, Descargas y Bluetooth, " +
                    "y archivos del tipo equivocado en Música, Películas, Imágenes y Documentos. " +
                    "No toca tus fotos de cámara (DCIM) ni carpetas de apps como WhatsApp.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Separar por año", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Ej. Pictures/2024, Music/2023",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = vm.byYear, onCheckedChange = { vm.byYear = it })
                }
            }
        }
        if (vm.scanning || groups == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else {
            val nonEmpty = OrgRule.entries.filter { groups[it].orEmpty().isNotEmpty() }
            if (nonEmpty.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("Todo está en su lugar.")
                    }
                }
            }
            items(nonEmpty) { rule ->
                val list = groups[rule].orEmpty()
                Card(Modifier.fillMaxWidth().clickable { vm.open = rule }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(kindIcon(rule.kind), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(rule.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${list.size} archivos → ${rule.target}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(formatSize(ctx, list.sumOf { it.size }), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
