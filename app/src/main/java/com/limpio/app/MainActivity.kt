package com.limpio.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder

class MainActivity : ComponentActivity() {

    private val cleaner: MainViewModel by viewModels()
    private val organizer: OrganizerViewModel by viewModels()
    private val explorer: ExplorerViewModel by viewModels()
    private val dups: DupViewModel by viewModels()
    private var hasPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx)
                    .components { add(VideoFrameDecoder.Factory()) }
                    .crossfade(true)
                    .build()
            }
            LimpioTheme {
                if (hasPermission) LimpioApp(cleaner, dups, organizer, explorer)
                else PermissionScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasPermission = checkPermission()
        cleaner.refreshStorage()
        if (!hasPermission) return
        // Al volver a la app los archivos pudieron cambiar desde otra app: actualizar.
        if (cleaner.result != null && cleaner.onHome &&
            System.currentTimeMillis() - cleaner.scannedAt > 20_000
        ) cleaner.scan()
        if (organizer.groups != null && organizer.open == null) organizer.scan()
        explorer.load()
        dups.pruneMissing()
    }

    private fun checkPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    @Composable
    private fun PermissionScreen() {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { hasPermission = checkPermission() }
        val request = {
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
                launcher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
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
                    "Para limpiar, organizar y explorar tus archivos, Limpio necesita acceso a " +
                        "todos los archivos. Todo ocurre en tu teléfono: la app no tiene acceso a internet.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { request() }, modifier = Modifier.fillMaxWidth()) {
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

@Composable
fun LimpioApp(
    cleaner: MainViewModel,
    dups: DupViewModel,
    organizer: OrganizerViewModel,
    explorer: ExplorerViewModel,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.CleaningServices, null) },
                    label = { Text("Limpiar") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.ContentCopy, null) },
                    label = { Text("Duplicados") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.AutoAwesome, null) },
                    label = { Text("Organizar") }
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Folder, null) },
                    label = { Text("Archivos") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
            when (tab) {
                0 -> CleanerTab(cleaner)
                1 -> DupTab(dups)
                2 -> OrganizerTab(organizer)
                else -> ExplorerTab(explorer)
            }
        }
    }
}
