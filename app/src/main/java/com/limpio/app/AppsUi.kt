package com.limpio.app

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class AppInfo(
    val pkg: String,
    val label: String,
    val version: String,
    val icon: ImageBitmap?,
    val apkSize: Long,
    val totalSize: Long,
    val lastUsed: Long,
    val installed: Long,
    val system: Boolean,
    val apkPaths: List<String>,
) {
    val unused: Boolean
        get() = lastUsed >= 0 && System.currentTimeMillis() - lastUsed > TimeUnit.DAYS.toMillis(90) && !system
}

enum class AppSort(val label: String) { SIZE("Tamaño"), UNUSED("Sin usar"), NAME("Nombre"), NEW("Recientes") }

class AppsViewModel(app: Application) : AndroidViewModel(app) {
    var apps by mutableStateOf<List<AppInfo>?>(null)
        private set
    var usageAccess by mutableStateOf(false)
        private set
    var sort by mutableStateOf(AppSort.SIZE)
    var showSystem by mutableStateOf(false)
    var selection by mutableStateOf<Set<String>>(emptySet())
    var message by mutableStateOf<String?>(null)
    private val ctx: Context get() = getApplication()

    fun hasUsageAccess(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun load() {
        viewModelScope.launch {
            usageAccess = hasUsageAccess()
            apps = withContext(Dispatchers.IO) { readApps(usageAccess) }
            selection = selection.filter { s -> apps.orEmpty().any { it.pkg == s } }.toSet()
        }
    }

    private var uninstallQueue: List<String> = emptyList()

    /** Al volver a la app: actualiza la lista y sigue con la siguiente app a desinstalar. */
    fun refreshIfLoaded() {
        if (apps != null) load()
        nextUninstall()
    }

    private fun nextUninstall() {
        val next = uninstallQueue.firstOrNull() ?: return
        uninstallQueue = uninstallQueue.drop(1)
        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$next")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun readApps(usage: Boolean): List<AppInfo> {
        val pm = ctx.packageManager
        val lastUsed: Map<String, Long> = if (!usage) emptyMap() else {
            try {
                val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                usm.queryAndAggregateUsageStats(now - TimeUnit.DAYS.toMillis(365 * 2), now).mapValues { it.value.lastTimeUsed }
            } catch (e: Exception) {
                emptyMap()
            }
        }
        val stats = if (usage) ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager else null
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            try {
                val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                val pi = pm.getPackageInfo(ai.packageName, 0)
                val apks = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
                val apkSize = apks.sumOf { File(it).length() }
                val total = stats?.let {
                    try {
                        val s = it.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                        s.appBytes + s.dataBytes
                    } catch (e: Exception) { null }
                } ?: apkSize
                AppInfo(
                    pkg = ai.packageName,
                    label = pm.getApplicationLabel(ai).toString(),
                    version = pi.versionName ?: "",
                    icon = try { pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap() } catch (e: Exception) { null },
                    apkSize = apkSize,
                    totalSize = total,
                    lastUsed = if (usage) lastUsed[ai.packageName] ?: 0L else -1L,
                    installed = pi.firstInstallTime,
                    system = system,
                    apkPaths = apks,
                )
            } catch (e: Exception) { null }
        }
    }

    fun visible(): List<AppInfo> {
        val list = apps.orEmpty().filter { showSystem || !it.system }
        return when (sort) {
            AppSort.SIZE -> list.sortedByDescending { it.totalSize }
            AppSort.UNUSED -> list.filter { it.unused }.sortedBy { it.lastUsed }
            AppSort.NAME -> list.sortedBy { it.label.lowercase() }
            AppSort.NEW -> list.sortedByDescending { it.installed }
        }
    }

    fun toggle(pkg: String) { selection = if (pkg in selection) selection - pkg else selection + pkg }

    fun uninstallSelected() {
        // Android pide confirmación de cada una; se piden una tras otra.
        uninstallQueue = selection.toList()
        selection = emptySet()
        nextUninstall()
    }

    fun backupSelected() {
        val sel = apps.orEmpty().filter { it.pkg in selection }
        selection = emptySet()
        viewModelScope.launch {
            val dir = File(Protection.rootPath, "Download/Respaldos APK").apply { mkdirs() }
            val saved = withContext(Dispatchers.IO) {
                sel.mapNotNull { a ->
                    try {
                        val base = "${a.label.replace(Regex("[\\\\/:*?\"<>|]"), "_")}_${a.version}"
                        val target = if (a.apkPaths.size == 1) {
                            FileOps.uniqueTarget(dir, "$base.apk").also { File(a.apkPaths[0]).copyTo(it) }
                        } else {
                            // App dividida en partes: se guardan juntas en un .apks
                            FileOps.uniqueTarget(dir, "$base.apks").also { t ->
                                ZipOutputStream(t.outputStream()).use { z ->
                                    a.apkPaths.forEach { p ->
                                        z.putNextEntry(ZipEntry(File(p).name)); File(p).inputStream().use { it.copyTo(z) }; z.closeEntry()
                                    }
                                }
                            }
                        }
                        target.absolutePath
                    } catch (e: Exception) { null }
                }
            }
            FileOps.rescan(ctx, saved)
            message = "Se respaldaron ${saved.size} de ${sel.size} apps en Descargas/Respaldos APK"
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(vm: AppsViewModel, onExit: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.load() }
    BackHandler { if (vm.selection.isNotEmpty()) vm.selection = emptySet() else onExit() }
    val list = vm.visible()
    val all = vm.apps.orEmpty().filter { !it.system }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.selection.isEmpty()) {
                IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                Text("Apps instaladas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            } else {
                IconButton(onClick = { vm.selection = emptySet() }) { Icon(Icons.Filled.Close, "Cancelar") }
                Text("${vm.selection.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.backupSelected() }) { Icon(Icons.Filled.Backup, "Respaldar APK") }
                if (vm.selection.size == 1) {
                    IconButton(onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${vm.selection.first()}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Icon(Icons.Filled.Info, "Información de la app") }
                }
                IconButton(onClick = { vm.uninstallSelected() }) { Icon(Icons.Filled.Delete, "Desinstalar") }
            }
        }
        val apps = vm.apps
        if (apps == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth().glass().padding(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${all.size} apps ocupan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatSize(ctx, all.sumOf { it.totalSize }), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    if (vm.usageAccess) {
                        val unused = all.filter { it.unused }
                        Column(Modifier.weight(1f)) {
                            Text("Sin usar en 3 meses (${unused.size})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatSize(ctx, unused.sumOf { it.totalSize }), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFFD9822B))
                        }
                    }
                }
            }
            if (!vm.usageAccess) {
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(16.dp)) {
                        Text("Ver tamaño real y último uso", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            "Sin este permiso solo se ve el tamaño del instalador. Activa \"Acceso a datos de uso\" para Archivos.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Dar acceso") }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSort.entries.forEach { s ->
                        if (s != AppSort.UNUSED || vm.usageAccess) {
                            FilterChip(selected = vm.sort == s, onClick = { vm.sort = s }, label = { Text(s.label) })
                        }
                    }
                    FilterChip(selected = vm.showSystem, onClick = { vm.showSystem = !vm.showSystem }, label = { Text("Del sistema") })
                }
                vm.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
            }
            if (list.isEmpty()) item { Text("No hay apps en esta vista.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list, key = { it.pkg }) { a ->
                val selected = a.pkg in vm.selection
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp))
                        .combinedClickable(
                            onClick = {
                                if (vm.selection.isNotEmpty()) vm.toggle(a.pkg)
                                else ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            },
                            onLongClick = { vm.toggle(a.pkg) }
                        )
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = selected, onCheckedChange = { vm.toggle(a.pkg) })
                    a.icon?.let { Image(it, null, Modifier.size(42.dp)) } ?: Box(Modifier.size(42.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    a.lastUsed < 0 -> "Versión ${a.version}"
                                    a.lastUsed == 0L -> "Sin uso registrado"
                                    else -> "Último uso: ${formatDate(a.lastUsed)}"
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                            )
                            if (a.unused) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Sin usar", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7A4300),
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFE3BF)).padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(formatSize(ctx, a.totalSize), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 6.dp))
                }
            }
        }
    }
}
