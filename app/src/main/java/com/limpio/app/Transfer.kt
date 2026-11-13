package com.limpio.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.DateFormat
import java.util.Date

object Net {
    /** IP del teléfono en la red Wi‑Fi (o en su punto de acceso), o null si no hay. */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { n -> listOf("wlan", "swlan", "ap", "eth").indexOfFirst { n.name.startsWith(it) }.let { if (it < 0) 99 else it } }
            .flatMap { n -> n.inetAddresses.toList().map { n.name to it } }
            .firstOrNull { (name, a) ->
                a is Inet4Address && a.isSiteLocalAddress && !name.startsWith("rmnet") && !name.startsWith("ccmni")
            }?.second?.hostAddress
    } catch (e: Exception) { null }
}

/** Estado de "Transferir a PC" que observa la pantalla. */
object Transfer {
    var running by mutableStateOf(false)
    var url by mutableStateOf("")
    var code by mutableStateOf("")
    var readOnly by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var events by mutableStateOf<List<String>>(emptyList())

    fun log(e: String) {
        val t = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        events = (listOf("$t · $e") + events).take(30)
    }

    fun start(ctx: Context) {
        error = null
        ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, TransferService::class.java).setAction(TransferService.ACTION_STOP))
    }

    /** Miniatura JPEG para la página web (fotos y videos). */
    fun thumbnail(f: File): ByteArray? = try {
        val bmp: Bitmap? = when (FileTypes.kindOf(f.name)) {
            FileKind.IMAGE -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, o)
                var s = 1
                while (minOf(o.outWidth, o.outHeight) / (s * 2) >= 300) s *= 2
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
            }
            FileKind.VIDEO -> {
                val m = MediaMetadataRetriever()
                try {
                    m.setDataSource(f.absolutePath)
                    if (Build.VERSION.SDK_INT >= 27) m.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
                    else m.getFrameAtTime(1_000_000)
                } finally { m.release() }
            }
            else -> null
        }
        bmp?.let { b -> ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 75, it); b.recycle() }.toByteArray() }
    } catch (e: Throwable) { null }
}

/** Mantiene el servidor vivo aunque salgas de la app, con aviso en la notificación. */
class TransferService : Service() {
    companion object {
        const val ACTION_STOP = "archivos.transfer.STOP"
        private const val CHANNEL = "transferencia"
        private const val NOTIF_ID = 42
        private const val IDLE_MS = 15 * 60_000L
    }

    private var server: TransferServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (server != null) return START_NOT_STICKY
        startInForeground("Iniciando…")
        val ip = Net.localIp()
        if (ip == null) {
            Transfer.error = "Conecta el teléfono a una red Wi‑Fi (la misma que la computadora)."
            stopSelf(); return START_NOT_STICKY
        }
        val code = TransferServer.newCode()
        val root = File(Protection.rootPath)
        var started: TransferServer? = null
        for (port in 8080..8090) {
            val s = TransferServer(
                null, port, root, code,
                readOnly = { Transfer.readOnly },
                isHidden = { Protection.isHiddenSystem(it) },
                isProtected = { Protection.isProtected(it) },
                thumbnail = { Transfer.thumbnail(it) },
                onChange = { paths -> FileOps.rescan(applicationContext, paths); scope.launch { StorageChanges.bump() } },
                onEvent = { e -> scope.launch { Transfer.log(e) } },
            )
            try { s.start(15_000, false); started = s; break } catch (e: Exception) { }
        }
        if (started == null) {
            Transfer.error = "No se pudo abrir el servidor (puertos ocupados)."
            stopSelf(); return START_NOT_STICKY
        }
        server = started
        Transfer.url = "http://$ip:${started.listeningPort}"
        Transfer.code = code
        Transfer.running = true
        Transfer.events = emptyList()
        Transfer.log("Listo en ${Transfer.url}")
        startInForeground("${Transfer.url} · código ${Transfer.code}")

        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "archivos:transfer").apply { acquire() }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "archivos:transfer").apply { acquire(2 * 60 * 60_000L) }

        // Se apaga solo tras 15 minutos sin que la computadora haga nada.
        scope.launch {
            while (true) {
                delay(30_000)
                val idle = System.currentTimeMillis() - (server?.lastActivity ?: 0)
                if (idle > IDLE_MS) { Transfer.log("Se apagó por inactividad"); stopSelf(); break }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transferir a PC", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Transferir a PC activo")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Detener", stop)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        try { wifiLock?.release() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        scope.cancel()
        Transfer.running = false
        Transfer.url = ""
        Transfer.code = ""
        super.onDestroy()
    }
}

@Composable
fun TransferScreen(onExit: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onExit() }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Transfer.start(ctx) }
    fun start() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else Transfer.start(ctx)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
            Text("Transferir a PC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if (Transfer.running) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val on = MaterialTheme.colorScheme.onPrimary
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF8CF0C8)))
                            Spacer(Modifier.width(8.dp))
                            Text("Activo · abre esto en el navegador de tu computadora", color = on, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.Wifi, null, tint = on)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Transfer.url.removePrefix("http://"), color = on, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { FileOps.copyPath(ctx, Transfer.url) }) { Icon(Icons.Filled.ContentCopy, "Copiar dirección", tint = on) }
                        }
                        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(on.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text("Código", color = on.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                            Text(Transfer.code.chunked(3).joinToString(" "), color = on, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().glass().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Pasa archivos entre tu teléfono y tu computadora por Wi‑Fi, sin cables ni programas.", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Desde el navegador de la PC podrás ver tus carpetas, descargar (también carpetas completas en ZIP), " +
                                "ver videos y subir archivos arrastrándolos. Solo funciona en tu red local: nada sale a internet.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Transfer.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Cómo conectarte", fontWeight = FontWeight.SemiBold)
                    listOf(
                        "Conecta la computadora a la misma red Wi‑Fi que el teléfono.",
                        "Abre el navegador y escribe la dirección de arriba.",
                        "Escribe el código cuando te lo pida.",
                    ).forEachIndexed { i, t ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) { Text("${i + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.width(10.dp))
                            Text(t, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Solo lectura")
                            Text("La PC puede descargar, pero no subir ni borrar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = Transfer.readOnly, onCheckedChange = { Transfer.readOnly = it })
                    }
                    Text("Se apaga solo tras 15 minutos sin uso.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                if (Transfer.running) {
                    OutlinedButton(
                        onClick = { Transfer.stop(ctx) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("Detener") }
                } else {
                    Button(onClick = { start() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Filled.Wifi, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Iniciar")
                    }
                }
            }
            if (Transfer.events.isNotEmpty()) {
                item { Text("Actividad", style = MaterialTheme.typography.titleSmall) }
                items(Transfer.events) { e -> Text(e, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
