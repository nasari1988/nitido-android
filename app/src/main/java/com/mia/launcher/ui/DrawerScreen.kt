package com.mia.launcher.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mia.launcher.LauncherViewModel
import com.mia.launcher.data.AppInfo
import com.mia.launcher.data.AppRepository
import com.mia.launcher.data.Group
import com.mia.launcher.data.Store
import com.mia.launcher.mia.MiaBrain
import com.mia.launcher.mia.MiaController

private sealed interface DrawerItem {
    data class AppItem(val app: AppInfo) : DrawerItem
    data class GroupItem(val group: Group, val apps: List<AppInfo>) : DrawerItem
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerScreen(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var tabId by remember { mutableStateOf("todas") }
    var editTab by remember { mutableStateOf<String?>(null) }
    var newTab by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(vm.drawerFocusSearch) { if (vm.drawerFocusSearch) try { focus.requestFocus() } catch (_: Exception) { } }

    val byKey = vm.byKey
    val visible = vm.apps.filter { it.key !in Store.hidden }
    val q = MiaBrain.norm(query)
    val items: List<DrawerItem> = when {
        q.isNotEmpty() -> vm.apps.filter { MiaBrain.norm(it.label).contains(q) }.map { DrawerItem.AppItem(it) }
        tabId == "todas" -> Store.groups.map { g -> DrawerItem.GroupItem(g, g.apps.mapNotNull { byKey[it] }) } +
            visible.map { DrawerItem.AppItem(it) }
        else -> Store.tabs.firstOrNull { it.id == tabId }?.apps?.mapNotNull { byKey[it] }?.sortedBy { it.label.lowercase() }
            ?.map { DrawerItem.AppItem(it) } ?: emptyList()
    }

    Box(Modifier.fillMaxSize().background(Color(0x66100E26))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(top = 24.dp)
                .glass(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), (Store.settings.glass + 0.2f).coerceAtMost(0.9f))
                .navigationBarsPadding().imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Box(
                Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
                    detectVerticalDragGestures { _, d -> if (d > 20f) vm.drawerOpen = false }
                }.clickable { vm.drawerOpen = false },
                contentAlignment = Alignment.Center
            ) { Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.6f))) }

            Row(
                Modifier.fillMaxWidth().height(52.dp).glassLight(RoundedCornerShape(26.dp)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Buscar en todas las pestañas", color = Muted, fontSize = 15.sp)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            items.firstOrNull()?.let { if (it is DrawerItem.AppItem) { AppRepository.launch(ctx, it.app); vm.goHome() } }
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (q.isEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { TabChip("Todas", vm.apps.size - Store.hidden.size, 0xFF5B3FD9, tabId == "todas", { tabId = "todas" }, null) }
                    items(Store.tabs, key = { it.id }) { t ->
                        TabChip(t.name, t.apps.count { it in byKey }, t.color, tabId == t.id, { tabId = t.id }, { editTab = t.id })
                    }
                    item {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape).clickable { newTab = true },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.Add, "Nueva pestaña", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            if (items.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (q.isNotEmpty()) "Sin resultados" else "Esta pestaña está vacía", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(if (q.isNotEmpty()) "Puedes buscarlo en la web o preguntarle a Mia." else "Mantén presionada una app en «Todas» y elige «Agregar a pestaña».",
                        color = Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp))
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(Store.settings.columns),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                if (q.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                            ActionChip("Buscar en la web") {
                                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(query)))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                try { ctx.startActivity(i) } catch (_: Exception) { }
                                vm.goHome()
                            }
                            ActionChip("Preguntar a Mia") { MiaController.handleInput(query, null); vm.goToMia() }
                        }
                    }
                }
                items(items, key = { d -> if (d is DrawerItem.AppItem) "a" + d.app.key else "g" + (d as DrawerItem.GroupItem).group.id }) { it ->
                    Box(contentAlignment = Alignment.TopCenter) {
                        when (it) {
                            is DrawerItem.AppItem -> AppIcon(it.app, { vm.menuApp = it.app }, onClick = { AppRepository.launch(ctx, it.app); vm.goHome() })
                            is DrawerItem.GroupItem -> FolderIcon(it.group, it.apps) { vm.openGroupId = it.group.id }
                        }
                    }
                }
            }
        }
    }

    if (newTab) NameDialog("Nueva pestaña", "", onDismiss = { newTab = false }) { Store.addTab(it); newTab = false }
    editTab?.let { id ->
        val t = Store.tabs.firstOrNull { it.id == id }
        if (t != null) NameDialog("Pestaña «${t.name}»", t.name, onDismiss = { editTab = null },
            onDelete = { Store.deleteTab(id); if (tabId == id) tabId = "todas"; editTab = null }) { Store.renameTab(id, it); editTab = null }
    }
    vm.openGroupId?.let { gid ->
        val g = Store.groups.firstOrNull { it.id == gid }
        if (g == null) { vm.openGroupId = null } else { GroupDialog(vm, g) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(name: String, count: Int, color: Long, selected: Boolean, onClick: () -> Unit, onLong: (() -> Unit)?) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color(color) else Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text("$count", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

@Composable
fun ActionChip(text: String, onClick: () -> Unit) {
    Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.glassLight(RoundedCornerShape(20.dp)).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp))
}

@Composable
private fun FolderIcon(group: Group, apps: List<AppInfo>, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(76.dp).clip(RoundedCornerShape(16.dp)).clickable { onClick() }.padding(vertical = 4.dp)
    ) {
        Box(
            Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.16f))
                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(18.dp)).padding(7.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                apps.take(4).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { Image(it.icon, null, Modifier.size(18.dp)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(group.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GroupDialog(vm: LauncherViewModel, g: Group) {
    val ctx = LocalContext.current
    var rename by remember { mutableStateOf(false) }
    val apps = g.apps.mapNotNull { vm.byKey[it] }
    Dialog(onDismissRequest = { vm.openGroupId = null }) {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp), 0.75f).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                ActionChip("Renombrar") { rename = true }
            }
            Spacer(Modifier.height(16.dp))
            if (apps.isEmpty()) Text("Grupo vacío. Mantén presionada una app y elige «Agregar a grupo».", color = Muted, fontSize = 14.sp)
            AppGrid(apps, 4) { a -> Store.toggleAppInGroup(g.id, a.key) }
            Spacer(Modifier.height(10.dp))
            Text("Mantén presionada una app para sacarla del grupo.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("Eliminar grupo") { Store.deleteGroup(g.id); vm.openGroupId = null }
                ActionChip("Cerrar") { vm.openGroupId = null }
            }
        }
    }
    if (rename) NameDialog("Renombrar grupo", g.name, onDismiss = { rename = false }) { Store.renameGroup(g.id, it); rename = false }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), 0.8f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth().height(50.dp).glassLight(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text("Nombre", color = Muted, fontSize = 15.sp)
                BasicTextField(text, { text = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Color.White), modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) ActionChip("Eliminar") { onDelete() }
                Spacer(Modifier.weight(1f))
                ActionChip("Cancelar") { onDismiss() }
                Text("Guardar", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White)
                        .clickable { if (text.isNotBlank()) onSave(text.trim()) }.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

/** Menú al mantener presionada una app. */
@Composable
fun AppActionsDialog(vm: LauncherViewModel, app: AppInfo) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf("main") }
    var newGroup by remember { mutableStateOf(false) }
    fun close() { vm.menuApp = null }
    Dialog(onDismissRequest = { close() }) {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), 0.8f).padding(vertical = 14.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(app.icon, null, Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Text(app.label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                when (mode) {
                    "main" -> {
                        item { MenuRow(if (app.key in Store.favorites) "Quitar de inicio" else "Agregar a inicio") { Store.toggleFavorite(app.key); close() } }
                        item { MenuRow(if (app.key in Store.dock) "Quitar del dock" else "Agregar al dock") { Store.toggleDock(app.key); close() } }
                        item { MenuRow("Agregar a pestaña…") { mode = "tabs" } }
                        item { MenuRow("Agregar a grupo…") { mode = "groups" } }
                        item { MenuRow(if (app.key in Store.hidden) "Mostrar en el cajón" else "Ocultar del cajón") { Store.toggleHidden(app.key); close() } }
                        item {
                            MenuRow("Información de la app") {
                                try {
                                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                } catch (_: Exception) { }
                                close()
                            }
                        }
                        item {
                            MenuRow("Desinstalar") {
                                try {
                                    @Suppress("DEPRECATION")
                                    ctx.startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                } catch (_: Exception) { }
                                close()
                            }
                        }
                    }
                    "tabs" -> {
                        items(Store.tabs, key = { it.id }) { t ->
                            MenuRow((if (app.key in t.apps) "✓  " else "") + t.name) { Store.toggleAppInTab(t.id, app.key) }
                        }
                        item { MenuRow("Listo") { close() } }
                    }
                    else -> {
                        items(Store.groups, key = { it.id }) { g ->
                            MenuRow((if (app.key in g.apps) "✓  " else "") + g.name) { Store.toggleAppInGroup(g.id, app.key) }
                        }
                        item { MenuRow("+ Nuevo grupo") { newGroup = true } }
                        item { MenuRow("Listo") { close() } }
                    }
                }
            }
        }
    }
    if (newGroup) NameDialog("Nuevo grupo", "", onDismiss = { newGroup = false }) { Store.addGroup(it, app.key); newGroup = false; close() }
}

@Composable
private fun MenuRow(text: String, onClick: () -> Unit) {
    Text(text, color = Color.White, fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 14.dp))
}
