package com.mia.launcher.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mia.launcher.LauncherViewModel
import com.mia.launcher.R
import com.mia.launcher.data.AppInfo
import com.mia.launcher.data.AppRepository
import com.mia.launcher.data.Store
import com.mia.launcher.mia.MiaController
import com.mia.launcher.mia.MiaMini
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val es = Locale("es", "MX")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIcon(app: AppInfo, onLongClick: () -> Unit, size: Dp = 58.dp, showLabel: Boolean = Store.settings.labels, onClick: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val glassy = Store.settings.iconStyle == "vidrio"
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(size + 18.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = { onClick?.invoke() ?: AppRepository.launch(ctx, app) }, onLongClick = onLongClick)
            .padding(vertical = 4.dp)
    ) {
        Box(
            Modifier.size(size).then(
                if (glassy) Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
                    .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape) else Modifier
            ),
            contentAlignment = Alignment.Center
        ) {
            Image(app.icon, app.label, Modifier.size(if (glassy) size * 0.74f else size))
        }
        if (showLabel) {
            Spacer(Modifier.height(5.dp))
            Text(app.label, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ShadowText)
        }
    }
}

@Composable
fun AppGrid(apps: List<AppInfo>, columns: Int, onLong: (AppInfo) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        apps.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { a -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { AppIcon(a, { onLong(a) }) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(1000) } }
    return now
}

private fun nextAlarm(ctx: Context): String? {
    val t = ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime ?: return null
    val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), java.time.ZoneId.systemDefault())
    return dt.format(DateTimeFormatter.ofPattern("EEE H:mm", es))
}

private fun nextEvent(ctx: Context): Pair<String, String>? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
    return try {
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString()).appendPath((now + 36L * 3600_000).toString()).build()
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN), null, null,
            CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext()) {
                val begin = c.getLong(1)
                if (begin >= now - 30 * 60_000) {
                    val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(begin), java.time.ZoneId.systemDefault())
                    return (c.getString(0) ?: "Evento") to dt.format(DateTimeFormatter.ofPattern("EEE H:mm", es))
                }
            }
            null
        }
    } catch (e: Exception) { null }
}

/** Pila de widgets propios: tocar para pasar al siguiente. */
@Composable
fun WidgetStack(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    val now = rememberNow()
    var idx by remember { mutableIntStateOf(0) }
    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { idx = 2 }
    val count = 4
    Box(Modifier.fillMaxWidth().height(170.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).offset(y = 18.dp).height(150.dp).glass(RoundedCornerShape(28.dp), 0.18f))
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).offset(y = 9.dp).height(150.dp).glass(RoundedCornerShape(28.dp), 0.25f))
        Box(
            Modifier.fillMaxWidth().height(150.dp).glass(RoundedCornerShape(28.dp))
                .clickable { idx = (idx + 1) % count }
        ) {
            when (idx) {
                0 -> Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 22.dp)) {
                    Text("Reloj", color = Color(0xFFFFC9B8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(now.format(DateTimeFormatter.ofPattern("H:mm")), color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Medium)
                    Text(nextAlarm(ctx)?.let { "Alarma $it" } ?: "Sin alarmas", color = Muted, fontSize = 14.sp)
                }
                1 -> Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { MiaController.battery / 100f }, modifier = Modifier.size(86.dp), strokeWidth = 7.dp,
                            color = if (MiaController.battery <= 15) Color(0xFFFF8A8A) else Mint, trackColor = Color.White.copy(alpha = 0.18f)
                        )
                        Text("${MiaController.battery}%", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text("Batería", color = Mint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (MiaController.charging) "Cargando" else "En uso", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    }
                }
                2 -> {
                    val ev = nextEvent(ctx)
                    Column(
                        Modifier.align(Alignment.CenterStart).padding(horizontal = 22.dp)
                            .clickable(enabled = ev == null) { calPerm.launch(Manifest.permission.READ_CALENDAR) }
                    ) {
                        Text("Agenda · " + now.format(DateTimeFormatter.ofPattern("EEE d MMM", es)), color = Color(0xFFA8F0DA), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        if (ev != null) {
                            Text(ev.first, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(ev.second, color = Muted, fontSize = 14.sp)
                        } else {
                            Text("Sin eventos próximos", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                            Text("Toca para permitir ver tu calendario", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
                else -> MiaMini { vm.goToMia() }
            }
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(count) { k ->
                    Box(Modifier.width(6.dp).height(if (k == idx) 18.dp else 6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (k == idx) Color.White else Color.White.copy(alpha = 0.45f)))
                }
            }
        }
    }
}

@Composable
fun HomeScreen(vm: LauncherViewModel, page: Int) {
    val now = rememberNow()
    val s = Store.settings
    val byKey = vm.byKey
    val favs = Store.favorites.mapNotNull { byKey[it] }
    val dock = Store.dock.mapNotNull { byKey[it] }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { _, d -> total += d; if (total < -60f) { vm.openDrawer(); total = 0f } }
                )
            }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { vm.settingsOpen = true }) }
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(now.format(DateTimeFormatter.ofPattern("EEEE, d MMM", es)).replaceFirstChar { it.titlecase(es) },
                    color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, style = ShadowText)
                Text("Batería ${MiaController.battery}%" + (if (MiaController.charging) " · cargando" else ""),
                    color = Color.White, fontSize = 15.sp, style = ShadowText)
            }
            Box(Modifier.size(44.dp).glassLight(CircleShape).clickable { vm.settingsOpen = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Settings, "Ajustes del launcher", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        WidgetStack(vm)
        Spacer(Modifier.weight(1f))
        if (favs.isEmpty()) {
            Text("Mantén presionada una app en el cajón para agregarla aquí", color = Muted, fontSize = 13.sp, style = ShadowText,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp))
        } else AppGrid(favs, s.columns) { vm.menuApp = it }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { k ->
                Box(Modifier.height(8.dp).width(if (k == page) 20.dp else 8.dp).clip(CircleShape)
                    .background(if (k == page) Color.White else Color.White.copy(alpha = 0.5f)))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (dock.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().height(80.dp).glass(RoundedCornerShape(32.dp)).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
            ) { dock.forEach { AppIcon(it, { vm.menuApp = it }, size = 54.dp, showLabel = false) } }
            Spacer(Modifier.height(12.dp))
        }
        Row(
            Modifier.fillMaxWidth().height(52.dp).glass(RoundedCornerShape(26.dp)).clickable { vm.openDrawer(search = true) }
                .padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text("Buscar apps y web", color = Muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(44.dp).glassLight(CircleShape).clickable { vm.goToMia(); MiaController.startListening(null) }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_mic), "Hablar con Mia", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}
