package com.limpio.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

fun formatSize(context: Context, bytes: Long): String = Formatter.formatFileSize(context, bytes)

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var hasPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LimpioTheme {
                if (hasPermission) App(vm) else PermissionScreen { requestLegacy() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasPermission = checkPermission()
        vm.refreshStorage()
    }

    private fun checkPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private var legacyRequest: (() -> Unit)? = null

    private fun requestLegacy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyRequest?.invoke()
        }
    }

    @Composable
    private fun PermissionScreen(onRequest: () -> Unit) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { hasPermission = checkPermission() }
        legacyRequest = {
            launcher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
        Scaffold { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.CleaningServices, null,
                    Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(20.dp))
                Text("Limpio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Para encontrar basura, duplicados y restos de apps, Limpio necesita " +
                        "acceso a todos los archivos. Todo se analiza en tu teléfono; " +
                        "la app no tiene acceso a internet.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                    Text("Dar permiso")
                }
            }
        }
    }
}

@Composable
fun LimpioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF5FD4B0))
        else -> lightColorScheme(primary = Color(0xFF0F8A6B))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun categoryIcon(c: Category): ImageVector = when (c) {
    Category.CACHE -> Icons.Filled.AutoDelete
    Category.LEFTOVERS -> Icons.Filled.Inventory2
    Category.EMPTY -> Icons.Filled.FolderOff
    Category.APKS -> Icons.Filled.Android
    Category.DUPLICATES -> Icons.Filled.ContentCopy
    Category.WHATSAPP_SENT -> Icons.AutoMirrored.Filled.Send
    Category.OLD_DOWNLOADS -> Icons.Filled.Download
    Category.LARGE -> Icons.Filled.SdStorage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    val open = vm.openCategory
    BackHandler(enabled = open != null) { vm.openCategory = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(open?.title ?: "Limpio") },
                navigationIcon = {
                    if (open != null) {
                        IconButton(onClick = { vm.openCategory = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                        }
                    }
                },
                actions = {
                    if (open == null && vm.result != null) {
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
            if (open == null) HomeScreen(vm) else CategoryScreen(vm, open)
            val p = vm.phase
            if (p is Phase.Cleaning) {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    title = { Text("Limpiando…") },
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
fun HomeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var confirmQuick by remember { mutableStateOf(false) }
    val result = vm.result
    val quickItems = vm.quickCleanItems()
    val quickSize = quickItems.sumOf { it.size }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { StorageCard(vm) }

        when (val p = vm.phase) {
            is Phase.Scanning -> item {
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
            else -> if (result == null) item {
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

        if (result != null && vm.phase !is Phase.Scanning) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Limpieza rápida", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (quickSize > 0 || quickItems.isNotEmpty())
                                "Caché, restos de apps, vacíos e instaladores ya usados: " +
                                    "${formatSize(ctx, quickSize)} en ${quickItems.size} elementos."
                            else "No hay basura segura que borrar. ¡Todo limpio!",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { confirmQuick = true },
                            enabled = quickItems.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Limpiar ${formatSize(ctx, quickSize)}") }
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

    if (confirmQuick) {
        AlertDialog(
            onDismissRequest = { confirmQuick = false },
            title = { Text("¿Limpiar ${formatSize(ctx, quickSize)}?") },
            text = { Text("Se borrarán ${quickItems.size} elementos de caché, restos de apps desinstaladas, carpetas vacías e instaladores de apps que ya tienes. No se tocan tus fotos ni documentos.") },
            confirmButton = {
                TextButton(onClick = { confirmQuick = false; vm.quickClean() }) { Text("Limpiar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmQuick = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
fun StorageCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val total = vm.storageTotal
    val used = total - vm.storageFree
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Almacenamiento", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
fun CategoryCard(cat: Category, count: Int, size: Long, onClick: () -> Unit) {
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

@Composable
fun CategoryScreen(vm: MainViewModel, cat: Category) {
    val ctx = LocalContext.current
    val list = vm.result?.items?.get(cat).orEmpty()
    val selected = vm.selectedItems(cat)
    val selSize = selected.sumOf { it.size }
    val allOn = list.isNotEmpty() && selected.size == list.size
    var confirm by remember { mutableStateOf(false) }
    val rootPrefix = Environment.getExternalStorageDirectory().absolutePath + "/"
    val dateFmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Column(Modifier.fillMaxSize()) {
        Text(
            cat.description,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Row(
            Modifier.fillMaxWidth().clickable { vm.setAll(cat, !allOn) }.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = allOn, onCheckedChange = { vm.setAll(cat, it) })
            Text("Seleccionar todo (${list.size})", modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            var lastGroup = -1
            list.forEach { item ->
                if (cat == Category.DUPLICATES && item.group != lastGroup) {
                    lastGroup = item.group
                    val g = item.group + 1
                    item(key = "g$g") {
                        Text(
                            "Grupo $g · ${formatSize(ctx, item.size)} cada copia",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp)
                        )
                    }
                }
                item(key = item.path) {
                    ItemRow(
                        item = item,
                        checked = vm.isSelected(cat, item.path),
                        location = item.path.removePrefix(rootPrefix).substringBeforeLast('/', ""),
                        date = dateFmt.format(Date(item.lastModified)),
                        onToggle = { vm.toggle(cat, item.path) }
                    )
                }
            }
        }
        HorizontalDivider()
        Button(
            onClick = { confirm = true },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)
        ) {
            Text("Borrar ${selected.size} · ${formatSize(ctx, selSize)}")
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Borrar ${selected.size} elementos?") },
            text = { Text("Se liberarán ${formatSize(ctx, selSize)}. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.deleteSelected(cat) }) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
fun ItemRow(item: CleanItem, checked: Boolean, location: String, date: String, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            if (location.isNotEmpty()) {
                Text(
                    location, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                listOfNotNull(item.note, date).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(formatSize(ctx, item.size), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
    }
}
