package com.limpio.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewAgenda
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

fun browseIcon(c: BrowseCategory): ImageVector = when (c) {
    BrowseCategory.DOWNLOADS -> Icons.Filled.Download
    BrowseCategory.IMAGES -> Icons.Filled.Image
    BrowseCategory.VIDEOS -> Icons.Filled.VideoLibrary
    BrowseCategory.AUDIO -> Icons.Filled.Headphones
    BrowseCategory.DOCUMENTS -> Icons.Filled.Description
    BrowseCategory.APPS -> Icons.Filled.Android
    BrowseCategory.ARCHIVES -> Icons.Filled.FolderZip
    BrowseCategory.RECENT -> Icons.Filled.History
}

@Composable
fun BrowseTab(vm: BrowseViewModel, onClean: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    LaunchedEffect(Unit) { vm.load() }
    BackHandler(enabled = vm.selection.isNotEmpty() || vm.stack.size > 1) {
        if (vm.selection.isNotEmpty()) vm.clearSelection() else vm.back()
    }
    Box(Modifier.fillMaxSize()) {
        if (vm.screen == Screen.Home) HomeScreen(vm, onClean) else ListScreen(vm, snackbar)
        if (vm.screen == Screen.Home) {
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        vm.busy?.let { label ->
            AlertDialog(
                onDismissRequest = {}, confirmButton = {},
                title = { Text(label) },
                text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            )
        }
        vm.zipPrompt?.let { z ->
            AlertDialog(
                onDismissRequest = { vm.zipPrompt = null },
                icon = { Icon(Icons.Filled.FolderZip, null) },
                title = { Text(z.name) },
                text = { Text("${formatSize(LocalContext.current, z.size)} · ¿Qué quieres hacer con este archivo comprimido?") },
                confirmButton = { TextButton(onClick = { vm.extract(z) }) { Text("Extraer aquí") } },
                dismissButton = {
                    val ctx = LocalContext.current
                    TextButton(onClick = { vm.zipPrompt = null; FileOps.openWith(ctx, z.path) }) { Text("Abrir con…") }
                }
            )
        }
    }
}

// =====================================================================
// Inicio
// =====================================================================

@Composable
private fun HomeScreen(vm: BrowseViewModel, onClean: () -> Unit) {
    val ctx = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Archivos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Surface(
                onClick = { vm.navigate(Screen.Search) },
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("Buscar en tu teléfono", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Categorías
        item {
            val cats = BrowseCategory.entries
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                cats.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { c ->
                            val stat = vm.catStats[c]
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                    .clickable { vm.openCategory(c) }.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val color = Color(c.color)
                                Box(
                                    Modifier.size(54.dp).clip(RoundedCornerShape(18.dp))
                                        .background(color.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) { Icon(browseIcon(c), null, tint = color) }
                                Spacer(Modifier.height(6.dp))
                                Text(c.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                Text(
                                    when {
                                        c == BrowseCategory.RECENT -> " "
                                        stat == null -> "…"
                                        else -> formatSize(ctx, stat.second)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Recientes
        if (vm.recent.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recientes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.openCategory(BrowseCategory.RECENT) }) { Text("Ver todo") }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(vm.recent, key = { it.path }) { e ->
                        Column(
                            Modifier.width(104.dp).clip(RoundedCornerShape(12.dp)).clickable {
                                if (e.isViewable) vm.openViewer(vm.recent, e) else FileOps.open(ctx, e.path)
                            }
                        ) {
                            FileThumb(e.path, false, modifier = Modifier.size(104.dp))
                            Text(
                                e.name, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Almacenamiento
        item { Text("Almacenamiento", style = MaterialTheme.typography.titleMedium) }
        items(vm.volumes, key = { it.path }) { v ->
            Card(Modifier.fillMaxWidth().clickable { vm.openFolder(v.path) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (v.primary) Icons.Filled.PhoneAndroid else Icons.Filled.SdCard, null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.label, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (v.total > 0) (v.total - v.free).toFloat() / v.total else 0f },
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatSize(ctx, v.free)} libres de ${formatSize(ctx, v.total)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth().clickable(onClick = onClean),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CleaningServices, null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Liberar espacio", fontWeight = FontWeight.SemiBold)
                        Text("Basura, restos de apps y duplicados", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }

        // Favoritos
        item {
            Text("Favoritos", style = MaterialTheme.typography.titleMedium)
            if (vm.favEntries.isEmpty()) {
                Text(
                    "Mantén presionado un archivo o carpeta y elige \"Agregar a favoritos\" para tenerlo aquí.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        items(vm.favEntries, key = { "fav" + it.path }) { e ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { if (e.isDir) vm.openFolder(e.path) else if (e.isViewable) vm.openViewer(listOf(e), e) else FileOps.open(ctx, e.path) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FileThumb(e.path, e.isDir, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Protection.relative(e.path).substringBeforeLast('/', "Almacenamiento"),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.toggleFavorite(e.path) }) {
                    Icon(Icons.Filled.Star, "Quitar de favoritos", tint = Color(0xFFFFB300))
                }
            }
        }
    }
}

// =====================================================================
// Listas: carpeta, categoría, búsqueda
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ListScreen(vm: BrowseViewModel, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val s = vm.screen
    val selecting = vm.selection.isNotEmpty()
    var menu by remember { mutableStateOf(false) }
    var selMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Entry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val title = when (s) {
        is Screen.Folder -> if (s.path == vm.root) "Almacenamiento interno" else File(s.path).name
        is Screen.Cat -> s.cat.label
        Screen.Search -> "Buscar"
        Screen.Home -> ""
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (selecting) {
                val sel = vm.selectedEntries()
                val single = sel.singleOrNull()
                TopAppBar(
                    title = { Text("${vm.selection.size}") },
                    navigationIcon = { IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Filled.Close, "Cancelar") } },
                    actions = {
                        IconButton(onClick = { FileOps.share(ctx, vm.selection.toList()) }) { Icon(Icons.Filled.Share, "Compartir") }
                        IconButton(onClick = { vm.startOp(OpType.MOVE) }) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Mover") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Eliminar") }
                        Box {
                            IconButton(onClick = { selMenu = true }) { Icon(Icons.Filled.MoreVert, "Más") }
                            DropdownMenu(expanded = selMenu, onDismissRequest = { selMenu = false }) {
                                MenuItem("Copiar a…", Icons.Filled.ContentCopy) { selMenu = false; vm.startOp(OpType.COPY) }
                                if (single != null) {
                                    MenuItem("Renombrar", Icons.Filled.Edit) { selMenu = false; renameTarget = single }
                                    MenuItem("Detalles", Icons.Filled.Info) { selMenu = false; vm.showDetails(single) }
                                    MenuItem(
                                        if (single.path in vm.favorites) "Quitar de favoritos" else "Agregar a favoritos",
                                        if (single.path in vm.favorites) Icons.Filled.Star else Icons.Filled.StarBorder
                                    ) { selMenu = false; vm.toggleFavorite(single.path) }
                                    if (!single.isDir) {
                                        MenuItem("Abrir con…", Icons.AutoMirrored.Filled.OpenInNew) { selMenu = false; vm.clearSelection(); FileOps.openWith(ctx, single.path) }
                                    }
                                    MenuItem("Copiar ruta", Icons.Filled.Link) { selMenu = false; vm.clearSelection(); FileOps.copyPath(ctx, single.path) }
                                }
                                MenuItem("Comprimir en ZIP", Icons.Filled.Archive) { selMenu = false; vm.compress() }
                                MenuItem("Seleccionar todo", Icons.Filled.SelectAll) { selMenu = false; vm.selectAll() }
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        if (s == Screen.Search) {
                            val focus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            TextField(
                                value = vm.query,
                                onValueChange = { vm.setSearch(it) },
                                placeholder = { Text("Nombre del archivo o carpeta") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                            )
                        } else {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                    },
                    actions = {
                        if (s != Screen.Search) {
                            IconButton(onClick = { vm.navigate(Screen.Search) }) { Icon(Icons.Filled.Search, "Buscar") }
                        }
                        IconButton(onClick = { vm.toggleGrid() }) {
                            Icon(if (vm.isGrid) Icons.Filled.ViewAgenda else Icons.Filled.GridView, "Cambiar vista")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                SortBy.entries.forEach { o ->
                                    MenuItem(
                                        "Ordenar por ${o.label.lowercase()}",
                                        if (vm.sortBy == o) Icons.Filled.CheckCircle else Icons.AutoMirrored.Filled.Sort
                                    ) { menu = false; vm.sort(o) }
                                }
                                HorizontalDivider()
                                MenuItem(
                                    if (vm.showHidden) "Ocultar archivos ocultos" else "Mostrar archivos ocultos",
                                    if (vm.showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
                                ) { menu = false; vm.toggleHidden() }
                                if (s is Screen.Folder) {
                                    MenuItem("Nueva carpeta", Icons.Filled.CreateNewFolder) { menu = false; newFolder = true }
                                    MenuItem("Calcular tamaño de carpetas", Icons.Filled.DataUsage) { menu = false; vm.computeFolderSizes() }
                                    MenuItem(
                                        if (s.path in vm.favorites) "Quitar carpeta de favoritos" else "Agregar carpeta a favoritos",
                                        if (s.path in vm.favorites) Icons.Filled.Star else Icons.Filled.StarBorder
                                    ) { menu = false; vm.toggleFavorite(s.path) }
                                }
                                MenuItem("Ir al inicio", Icons.Filled.Home) { menu = false; vm.goHome() }
                            }
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
                            "${if (type == OpType.MOVE) "Mover" else "Copiar"} ${paths.size} elementos: " +
                                if (s is Screen.Folder) "¿aquí?" else "abre la carpeta de destino",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(Modifier.padding(top = 8.dp)) {
                            OutlinedButton(onClick = { vm.cancelOp() }) { Text("Cancelar") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { vm.pasteHere() },
                                enabled = s is Screen.Folder,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (type == OpType.MOVE) "Mover aquí" else "Copiar aquí") }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (s is Screen.Folder) Breadcrumbs(vm, s.path)
            if (vm.sizing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                when {
                    vm.loading && vm.entries.isEmpty() ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    vm.entries.isEmpty() -> Text(
                        when {
                            s == Screen.Search && vm.query.trim().length < 2 -> "Escribe al menos 2 letras"
                            s == Screen.Search -> "Sin resultados para \"${vm.query}\""
                            else -> "Aquí no hay nada"
                        },
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    vm.isGrid -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(108.dp),
                        contentPadding = PaddingValues(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(vm.entries, key = { it.path }) { e -> EntryTile(vm, e, selecting) }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(vm.entries, key = { it.path }) { e -> EntryRow(vm, e, selecting, s !is Screen.Folder) }
                    }
                }
            }
        }
    }

    if (newFolder) {
        NameDialog("Nueva carpeta", "", onDismiss = { newFolder = false }) { newFolder = false; vm.newFolder(it) }
    }
    renameTarget?.let { e ->
        NameDialog("Renombrar", e.name, onDismiss = { renameTarget = null }) { renameTarget = null; vm.rename(e.path, it) }
    }
    if (confirmDelete) {
        val n = vm.selection.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar $n elementos?") },
            text = {
                Text(
                    "Se eliminarán de forma permanente, incluido el contenido de las carpetas. " +
                        "No pasan por la papelera y no se pueden recuperar."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(vm.selection) }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/** Ruta tocable: Interno › DCIM › Camera */
@Composable
private fun Breadcrumbs(vm: BrowseViewModel, path: String) {
    val parts = ArrayList<Pair<String, String>>()
    val base = if (FileOps.isInside(path, vm.root)) vm.root else path.substringBeforeLast("/").ifEmpty { path }
    parts += (if (base == vm.root) "Interno" else File(base).name) to base
    var acc = base
    path.removePrefix(base).split('/').filter { it.isNotEmpty() }.forEach {
        acc = "$acc/$it"
        parts += it to acc
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(Int.MAX_VALUE))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        parts.forEachIndexed { i, (name, p) ->
            val last = i == parts.lastIndex
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !last) { vm.openFolder(p) }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            )
            if (!last) Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun subtitle(ctx: android.content.Context, vm: BrowseViewModel, e: Entry): String {
    val size = if (e.isDir) vm.folderSizes[e.path]?.let { formatSize(ctx, it) } else formatSize(ctx, e.size)
    return listOfNotNull(
        if (e.isProtected) "Del sistema" else null,
        if (e.isDir) "${e.children} elementos" else null,
        size,
        formatDate(e.modified),
    ).joinToString(" · ")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(vm: BrowseViewModel, e: Entry, selecting: Boolean, showLocation: Boolean) {
    val ctx = LocalContext.current
    val selected = e.path in vm.selection
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = { if (selecting) vm.toggleSelect(e) else vm.openEntry(e) },
                onLongClick = { vm.toggleSelect(e) }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(if (e.hidden) 0.6f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            FileThumb(e.path, e.isDir, 48.dp)
            if (selected) SelectedBadge(Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (e.isDir) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                subtitle(ctx, vm, e), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
            )
            if (showLocation) {
                Text(
                    Protection.relative(e.path).substringBeforeLast('/', "Almacenamiento"),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (e.path in vm.favorites) Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = Color(0xFFFFB300))
        if (e.isProtected) Icon(Icons.Filled.Lock, "Protegido", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryTile(vm: BrowseViewModel, e: Entry, selecting: Boolean) {
    val ctx = LocalContext.current
    val selected = e.path in vm.selection
    Column(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = { if (selecting) vm.toggleSelect(e) else vm.openEntry(e) },
                onLongClick = { vm.toggleSelect(e) }
            )
            .padding(4.dp)
            .alpha(if (e.hidden) 0.6f else 1f)
    ) {
        Box {
            FileThumb(e.path, e.isDir, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            if (selected) SelectedBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
            if (e.isProtected) Icon(
                Icons.Filled.Lock, null,
                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            e.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp)
        )
        if (e.kind != FileKind.IMAGE) {
            Text(
                if (e.isDir) vm.folderSizes[e.path]?.let { formatSize(ctx, it) } ?: "${e.children} elementos"
                else formatSize(ctx, e.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}

@Composable
private fun SelectedBadge(modifier: Modifier) {
    Icon(
        Icons.Filled.CheckCircle, "Seleccionado",
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
    )
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Detalles de un archivo o carpeta (se puede abrir desde la lista o desde el visor). */
@Composable
fun DetailsDialog(vm: BrowseViewModel) {
    val d = vm.details ?: return
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { vm.details = null },
        icon = { Icon(Icons.Filled.Info, null) },
        title = { Text("Detalles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.rows.forEach { (k, v) ->
                    Column {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (d.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { vm.details = null }) { Text("Cerrar") } },
        dismissButton = { TextButton(onClick = { FileOps.copyPath(ctx, d.entry.path) }) { Text("Copiar ruta") } }
    )
}
