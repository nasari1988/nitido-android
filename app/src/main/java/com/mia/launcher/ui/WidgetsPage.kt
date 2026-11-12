package com.mia.launcher.ui

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.mia.launcher.LauncherViewModel
import com.mia.launcher.data.Store
import com.mia.launcher.mia.MiaMini

/** Anfitrión de widgets de otras apps (WhatsApp, Spotify, Google…). */
object WidgetHost {
    const val HOST_ID = 4242
    lateinit var host: AppWidgetHost
        private set
    lateinit var manager: AppWidgetManager
        private set

    fun init(ctx: Context) {
        if (!::host.isInitialized) {
            host = AppWidgetHost(ctx.applicationContext, HOST_ID)
            manager = AppWidgetManager.getInstance(ctx.applicationContext)
        }
    }
}

@Composable
fun WidgetsPage(vm: LauncherViewModel, onAdd: (AppWidgetProviderInfo) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Widgets", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, style = ShadowText)
        WidgetStack(vm)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(110.dp).glass(RoundedCornerShape(28.dp))) { MiaMini { vm.goToMia() } }

        Store.widgetIds.forEach { id ->
            val info = WidgetHost.manager.getAppWidgetInfo(id)
            if (info != null) {
                val h = with(density) { info.minHeight.toDp() }.coerceIn(90.dp, 420.dp)
                Box(Modifier.fillMaxWidth().height(h + 16.dp).glass(RoundedCornerShape(28.dp)).padding(8.dp)) {
                    AndroidView(
                        factory = { c -> WidgetHost.host.createView(c, id, info).apply { setAppWidget(id, info) } },
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        Modifier.align(Alignment.TopEnd).size(30.dp).glassLight(CircleShape)
                            .clickable { WidgetHost.host.deleteAppWidgetId(id); Store.removeWidget(id) },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Close, "Quitar widget", tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().height(56.dp).glassLight(RoundedCornerShape(24.dp)).clickable { picker = true },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Add, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("Agregar widget de una app", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(24.dp))
    }
    if (picker) WidgetPicker(onDismiss = { picker = false }) { picker = false; onAdd(it) }
}

@Composable
private fun WidgetPicker(onDismiss: () -> Unit, onPick: (AppWidgetProviderInfo) -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val providers = remember {
        WidgetHost.manager.installedProviders.map { p ->
            val label = runCatching { p.loadLabel(pm) }.getOrNull() ?: p.provider.className
            val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0)).toString() }.getOrDefault(p.provider.packageName)
            val icon = runCatching { pm.getApplicationIcon(p.provider.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            Triple(p, "$app · $label", icon)
        }.sortedBy { it.second.lowercase() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), 0.85f).padding(vertical = 16.dp)) {
            Text("Elige un widget", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(providers) { (p, name, icon) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(p) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (icon != null) Image(icon, null, Modifier.size(32.dp)) else Spacer(Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(name, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
