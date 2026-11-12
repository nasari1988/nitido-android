package com.limpio.app

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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

fun categoryIcon(c: Category): ImageVector = when (c) {
    Category.CACHE -> Icons.Filled.AutoDelete
    Category.LEFTOVERS -> Icons.Filled.Inventory2
    Category.EMPTY -> Icons.Filled.FolderOff
    Category.APKS -> Icons.Filled.Android
    Category.DUPLICATES -> Icons.Filled.ContentCopy
    Category.SIMILAR_VIDEOS -> Icons.Filled.VideoLibrary
    Category.WHATSAPP_SENT -> Icons.AutoMirrored.Filled.Send
    Category.OLD_DOWNLOADS -> Icons.Filled.Download
    Category.LARGE -> Icons.Filled.SdStorage
}

private const val PERMANENT_NOTE =
    "Se eliminarán de forma permanente: no pasan por ninguna papelera y no se pueden recuperar."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanerTab(vm: MainViewModel) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    // Si otra pestaña movió o borró archivos, el análisis se rehace solo.
    LaunchedEffect(StorageChanges.version) {
        if (vm.result != null && vm.onHome && vm.scannedVersion != StorageChanges.version) vm.scan()
    }
    val open = vm.openCategory
    val quick = vm.showQuick
    BackHandler(enabled = open != null || quick) {
        vm.openCategory = null; vm.showQuick = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (quick) "Limpieza rápida" else open?.title ?: "Limpiar") },
                navigationIcon = {
                    if (open != null || quick) {
                        IconButton(onClick = { vm.openCategory = null; vm.showQuick = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                        }
                    }
                },
                actions = {
                    if (open == null && !quick && vm.result != null) {
                        IconButton(onClick = { vm.scan() }) {
                            Icon(Icons.Filled.Refresh, "Volver a analizar")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                quick -> ReviewList(
                    header = "Caché, restos de apps, carpetas vacías e instaladores de apps que ya " +
                        "tienes. Revisa y desmarca lo que quieras conservar.",
                    items = vm.quickCleanItems(),
                    selected = vm.quickSelection,
                    onToggle = vm::toggleQuick,
                    onSetAll = vm::setAllQuick,
                    grouped = false,
                    destructive = true,
                    actionLabel = { n, s -> "Eliminar $n · ${formatSize(ctx, s)}" },
                    confirmTitle = { n -> "¿Eliminar $n elementos?" },
                    confirmText = { s -> "Liberarás ${formatSize(ctx, s)}. $PERMANENT_NOTE" },
                    onConfirm = { vm.quickClean() },
                )
                open != null -> ReviewList(
                    header = open.description,
                    items = vm.result?.items?.get(open).orEmpty(),
                    selected = vm.selection[open].orEmpty(),
                    onToggle = { vm.toggle(open, it) },
                    onSetAll = { vm.setAll(open, it) },
                    grouped = open in GROUPED_CATEGORIES,
                    destructive = true,
                    actionLabel = { n, s -> "Eliminar $n · ${formatSize(ctx, s)}" },
                    confirmTitle = { n -> "¿Eliminar $n elementos?" },
                    confirmText = { s -> "Liberarás ${formatSize(ctx, s)}. $PERMANENT_NOTE" },
                    onConfirm = { vm.deleteSelected(open) },
                )
                else -> CleanerHome(vm)
            }
            val p = vm.phase
            if (p is Phase.Cleaning) {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    title = { Text("Eliminando…") },
                    text = {
                        LinearProgressIndicator(
                            progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun CleanerHome(vm: MainViewModel) {
    val ctx = LocalContext.current
    val result = vm.result
    val quickItems = vm.quickCleanItems()
    val quickSize = quickItems.sumOf { it.size }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { StorageCard(vm) }

        val p = vm.phase
        if (p is Phase.Scanning) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Analizando… ${p.count} archivos")
                    }
                }
            }
        } else if (result == null) {
            item {
                Button(
                    onClick = { vm.scan() },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(Icons.Filled.CleaningServices, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Analizar almacenamiento")
                }
            }
        }

        if (result != null && p !is Phase.Scanning) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "Limpieza rápida",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (quickItems.isNotEmpty())
                                "${formatSize(ctx, quickSize)} en ${quickItems.size} elementos de " +
                                    "caché, restos de apps, vacíos e instaladores ya usados."
                            else "No hay basura segura que borrar. ¡Todo limpio!",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.showQuick = true },
                            enabled = quickItems.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Revisar y limpiar") }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${result.filesScanned} archivos analizados en " +
                                "${"%.1f".format(result.durationMs / 1000.0)} s",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item {
                Text(
                    "Revisar por categoría",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(Category.entries.toList()) { cat ->
                val list = result.items[cat].orEmpty()
                CategoryCard(cat, list.size, vm.totalSize(cat)) { vm.openCategory = cat }
            }
        }
    }
}

@Composable
private fun StorageCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val total = vm.storageTotal
    val used = total - vm.storageFree
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Almacenamiento",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text("${formatSize(ctx, vm.storageFree)} libres", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (total > 0) used.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().height(10.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatSize(ctx, used)} usados de ${formatSize(ctx, total)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CategoryCard(cat: Category, count: Int, size: Long, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable(enabled = count > 0, onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(categoryIcon(cat), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(cat.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (count == 0) "Nada encontrado" else "$count elementos",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (size > 0) Text(formatSize(ctx, size), fontWeight = FontWeight.SemiBold)
        }
    }
}
