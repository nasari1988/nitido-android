package com.mia.launcher.mia

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.mia.launcher.MiaApp
import com.mia.launcher.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/** Modelo de voz offline (Vosk, español pequeño ~40 MB) para detectar «Oye Mia» sin internet. */
object VoskModel {
    private const val NAME = "vosk-model-small-es-0.42"
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/$NAME.zip"

    fun dir(ctx: Context) = File(ctx.filesDir, "vosk/$NAME")
    fun ready(ctx: Context) = File(dir(ctx), "conf").exists() || File(dir(ctx), "am").exists()

    /** Descarga y descomprime el modelo. onProgress va de 0 a 100. */
    suspend fun download(ctx: Context, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val zip = File(ctx.cacheDir, "$NAME.zip")
            var conn = URL(URL_ZIP).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            if (conn.responseCode in 300..399) {
                val loc = conn.getHeaderField("Location"); conn.disconnect()
                conn = URL(loc).openConnection() as HttpURLConnection
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 40_000_000L
            conn.inputStream.use { input ->
                FileOutputStream(zip).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        onProgress(((done * 90) / total).toInt().coerceIn(0, 90))
                    }
                }
            }
            conn.disconnect()
            val base = File(ctx.filesDir, "vosk"); base.mkdirs()
            ZipInputStream(zip.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val f = File(base, e.name)
                    if (!f.canonicalPath.startsWith(base.canonicalPath)) continue
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); FileOutputStream(f).use { zin.copyTo(it) } }
                }
            }
            zip.delete()
            onProgress(100)
            ready(ctx)
        } catch (e: Exception) {
            false
        }
    }
}

/** Servicio en primer plano que escucha «Oye Mia» y abre a Mia (incluso con la pantalla bloqueada). */
class WakeWordService : Service() {
    private var model: Model? = null
    private var speech: SpeechService? = null
    private val worker = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { pause(); return START_STICKY }
        }
        goForeground()
        resume()
        return START_STICKY
    }

    private fun goForeground() {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MiaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, MiaApp.WAKE_CHANNEL)
            .setSmallIcon(R.drawable.ic_mia_note)
            .setContentTitle("Mia te escucha")
            .setContentText("Di «Oye Mia» para hablar con ella")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_mia_note), "Dejar de escuchar", stop).build())
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun resume() {
        if (speech != null) return
        if (!VoskModel.ready(this) || !MiaController.hasMic(this)) return
        worker.execute {
            try {
                if (model == null) model = Model(VoskModel.dir(this).absolutePath)
                val grammar = "[\"oye mia\", \"oye mía\", \"hola mia\", \"hola mía\", \"[unk]\"]"
                val rec = Recognizer(model, 16000f, grammar)
                val s = SpeechService(rec, 16000f)
                speech = s
                s.startListening(listener)
            } catch (e: Exception) {
                speech = null
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = check(hypothesis)
        override fun onResult(hypothesis: String?) = check(hypothesis)
        override fun onFinalResult(hypothesis: String?) = check(hypothesis)
        override fun onError(exception: Exception?) { pause() }
        override fun onTimeout() { pause(); resume() }
    }

    private fun check(h: String?) {
        val t = h ?: return
        if (t.contains("mia") || t.contains("mía")) {
            pause()
            startActivity(
                Intent(this, MiaActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MiaActivity.EXTRA_FROM_WAKE, true)
            )
        }
    }

    private fun pause() {
        val s = speech ?: return
        speech = null
        try { s.stop(); s.shutdown() } catch (_: Exception) { }
    }

    private fun stopEverything() {
        pause()
        try { model?.close() } catch (_: Exception) { }
        model = null
    }

    override fun onDestroy() {
        stopEverything()
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.mia.launcher.STOP_WAKE"
        const val ACTION_PAUSE = "com.mia.launcher.PAUSE_WAKE"
        const val ACTION_RESUME = "com.mia.launcher.RESUME_WAKE"
        private const val NOTIF_ID = 7

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, WakeWordService::class.java).setAction(ACTION_RESUME))
            } catch (_: Exception) { }
        }

        fun resume(ctx: Context) {
            try { ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(ACTION_RESUME)) } catch (_: Exception) { }
        }

        fun stop(ctx: Context) {
            try { ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) { }
        }

        fun pause(ctx: Context) {
            try { ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(ACTION_PAUSE)) } catch (_: Exception) { }
        }
    }
}
