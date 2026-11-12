package com.mia.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mia.launcher.data.AppInfo
import com.mia.launcher.data.AppRepository
import com.mia.launcher.data.Store
import com.mia.launcher.mia.VoskModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    var apps by mutableStateOf(listOf<AppInfo>())
        private set
    val byKey: Map<String, AppInfo> get() = apps.associateBy { it.key }

    var drawerOpen by mutableStateOf(false)
    var drawerFocusSearch by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)
    var menuApp by mutableStateOf<AppInfo?>(null)
    var openGroupId by mutableStateOf<String?>(null)
    var homeSignal by mutableIntStateOf(0)
        private set
    var miaSignal by mutableIntStateOf(0)
        private set

    var modelProgress by mutableStateOf(-1)
        private set
    var modelReady by mutableStateOf(VoskModel.ready(app))
        private set

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    init {
        refresh()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(app, pkgReceiver, f, ContextCompat.RECEIVER_EXPORTED)
    }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.Default) { AppRepository.load(getApplication()) }
            apps = list
            if (!Store.defaultsDone && list.isNotEmpty()) chooseDefaults(list)
        }
    }

    private fun chooseDefaults(list: List<AppInfo>) {
        val ctx: Context = getApplication()
        val wanted = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Intent.ACTION_VIEW).setType("image/*"),
        )
        val dock = wanted.mapNotNull { AppRepository.defaultPackageFor(ctx, it) }
            .mapNotNull { pkg -> list.firstOrNull { it.packageName == pkg }?.key }
            .distinct()
        val favNames = listOf("calendar", "calendario", "maps", "mapas", "clock", "reloj", "spotify", "youtube", "whatsapp", "settings", "ajustes", "configuración")
        val favs = list.filter { a -> a.key !in dock && favNames.any { a.label.lowercase().contains(it) } }.take(8).map { it.key }
        Store.setDefaults(dock, favs)
    }

    fun goHome() {
        drawerOpen = false; settingsOpen = false; menuApp = null; openGroupId = null
        homeSignal++
    }

    fun goToMia() { drawerOpen = false; settingsOpen = false; miaSignal++ }

    fun openDrawer(search: Boolean = false) { drawerFocusSearch = search; drawerOpen = true }

    fun downloadModel(onDone: (Boolean) -> Unit) {
        if (modelProgress in 0..99) return
        modelProgress = 0
        viewModelScope.launch {
            val ok = VoskModel.download(getApplication()) { p -> viewModelScope.launch { modelProgress = p } }
            modelReady = ok
            modelProgress = -1
            onDone(ok)
        }
    }

    override fun onCleared() {
        try { getApplication<Application>().unregisterReceiver(pkgReceiver) } catch (_: Exception) { }
        super.onCleared()
    }
}
