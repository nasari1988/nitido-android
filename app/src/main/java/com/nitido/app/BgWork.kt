package com.nitido.app

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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Keeps long jobs (generating, refining, enhancing, downloading models) alive when the user
 * switches apps or turns the screen off: a foreground service with a progress notification
 * and a Stop button, plus a partial wake lock so the CPU/GPU keep working.
 *
 * Jobs run in [scope], which is not tied to any screen, so leaving the screen doesn't cancel them.
 */
object BgWork {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private const val CHANNEL_RUN = "work"
    private const val CHANNEL_DONE = "done"
    const val ID_RUN = 1
    private const val ID_DONE = 2
    const val ACTION_STOP = "com.nitido.app.STOP"

    @Volatile var onStop: (() -> Unit)? = null
    /** A job is running (this survives leaving and reopening a screen). */
    @Volatile var busy = false
    @Volatile private var title = ""
    @Volatile private var target: Class<*> = MainActivity::class.java
    @Volatile private var lastUpdate = 0L

    /** Starts the foreground service. [stop] is called when the user taps Stop in the notification. */
    fun start(ctx: Context, title: String, target: Class<*>, stop: () -> Unit) {
        val app = ctx.applicationContext
        this.title = title
        this.target = target
        onStop = stop
        busy = true
        channels(app)
        app.getSystemService(NotificationManager::class.java).cancel(ID_DONE)
        try {
            ContextCompat.startForegroundService(app, Intent(app, BgService::class.java))
        } catch (t: Throwable) {
            // Not allowed right now (rare); the job still runs while the app is open.
        }
    }

    fun update(ctx: Context, text: String, pct: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdate < 700 && pct in 1..99) return
        lastUpdate = now
        val app = ctx.applicationContext
        if (!canNotify(app)) return
        app.getSystemService(NotificationManager::class.java).notify(ID_RUN, running(app, text, pct))
    }

    /** Stops the service and, if [message] is given, leaves a notification that opens the app. */
    fun finish(ctx: Context, message: String?) {
        val app = ctx.applicationContext
        onStop = null
        busy = false
        app.stopService(Intent(app, BgService::class.java))
        if (message != null && canNotify(app)) {
            val n = NotificationCompat.Builder(app, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(open(app))
                .setAutoCancel(true)
                .build()
            app.getSystemService(NotificationManager::class.java).notify(ID_DONE, n)
        }
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun running(ctx: Context, text: String, pct: Int): Notification {
        val stop = PendingIntent.getService(ctx, 1, Intent(ctx, BgService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(ctx, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, pct.coerceIn(0, 100), pct <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(ctx))
            .addAction(0, ctx.getString(R.string.stop), stop)
            .build()
    }

    private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RUN, ctx.getString(R.string.notif_channel_run), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, ctx.getString(R.string.notif_channel_done), NotificationManager.IMPORTANCE_DEFAULT))
    }

    internal fun currentTitle() = title
}

class BgService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == BgWork.ACTION_STOP) {
            BgWork.onStop?.invoke()
            return START_NOT_STICKY
        }
        val n = BgWork.running(this, getString(R.string.notif_working), 0)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, BgWork.ID_RUN, n, type)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nitido:work").apply {
                    setReferenceCounted(false)
                    acquire(3 * 60 * 60 * 1000L)   // safety cap: 3 h
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }
}
