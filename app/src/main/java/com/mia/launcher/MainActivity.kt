package com.mia.launcher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.mia.launcher.data.Store
import com.mia.launcher.mia.MiaPanel
import com.mia.launcher.mia.VoskModel
import com.mia.launcher.mia.WakeWordService
import com.mia.launcher.ui.AppActionsDialog
import com.mia.launcher.ui.DrawerScreen
import com.mia.launcher.ui.HomeScreen
import com.mia.launcher.ui.MiaTheme
import com.mia.launcher.ui.SettingsScreen
import com.mia.launcher.ui.WidgetHost
import com.mia.launcher.ui.WidgetsPage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()
    private var pendingWidgetId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WidgetHost.init(this)
        setContent { MiaTheme { LauncherRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) vm.goHome()
    }

    override fun onStart() {
        super.onStart()
        try { WidgetHost.host.startListening() } catch (_: Exception) { }
    }

    override fun onStop() {
        super.onStop()
        try { WidgetHost.host.stopListening() } catch (_: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        if (Store.settings.wakeWord && VoskModel.ready(this)) WakeWordService.start(this)
    }

    // ---------------- Widgets de otras apps
    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = WidgetHost.host.allocateAppWidgetId()
        pendingWidgetId = id
        if (WidgetHost.manager.bindAppWidgetIdIfAllowed(id, info.provider)) {
            configureOrAdd(id, info)
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider),
                REQ_BIND
            )
        }
    }

    private fun configureOrAdd(id: Int, info: AppWidgetProviderInfo?) {
        if (info?.configure != null) {
            try {
                WidgetHost.host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null)
                return
            } catch (_: Exception) { }
        }
        Store.addWidget(id)
    }

    @Deprecated("Usado para enlazar widgets")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
        when (requestCode) {
            REQ_BIND -> if (resultCode == Activity.RESULT_OK) configureOrAdd(id, WidgetHost.manager.getAppWidgetInfo(id))
            else { WidgetHost.host.deleteAppWidgetId(id); Toast.makeText(this, "No se agregó el widget", Toast.LENGTH_SHORT).show() }
            REQ_CONFIG -> if (resultCode == Activity.RESULT_OK) Store.addWidget(id) else WidgetHost.host.deleteAppWidgetId(id)
        }
    }

    @Composable
    private fun LauncherRoot() {
        val pager = rememberPagerState(initialPage = 1) { 3 }
        val scope = rememberCoroutineScope()
        LaunchedEffect(vm.homeSignal) { if (vm.homeSignal > 0) pager.animateScrollToPage(1) }
        LaunchedEffect(vm.miaSignal) { if (vm.miaSignal > 0) pager.animateScrollToPage(0) }

        Box(Modifier.fillMaxSize()) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                when (page) {
                    0 -> MiaPanel(this@MainActivity) { vm.settingsOpen = true }
                    1 -> HomeScreen(vm, pager.currentPage)
                    else -> WidgetsPage(vm) { addWidget(it) }
                }
            }
            AnimatedVisibility(vm.drawerOpen, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                DrawerScreen(vm)
            }
            AnimatedVisibility(vm.settingsOpen, enter = fadeIn(), exit = fadeOut()) {
                SettingsScreen(vm)
            }
            vm.menuApp?.let { AppActionsDialog(vm, it) }
        }

        BackHandler(enabled = vm.drawerOpen || vm.settingsOpen || pager.currentPage != 1) {
            when {
                vm.settingsOpen -> vm.settingsOpen = false
                vm.drawerOpen -> vm.drawerOpen = false
                else -> scope.launch { pager.animateScrollToPage(1) }
            }
        }
    }

    companion object {
        private const val REQ_BIND = 101
        private const val REQ_CONFIG = 102
    }
}
