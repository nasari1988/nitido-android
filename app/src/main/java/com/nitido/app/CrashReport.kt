package com.nitido.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * If the app closed by itself last time (crash, out of memory, killed by the system),
 * explain it once on the next start, with details the user can copy and send.
 */
object CrashReport {
    private const val KEY = "last_exit_seen"
    @Volatile private var installed = false

    /** Keeps the stack trace of app (Kotlin) crashes so the next start can show it. */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val file = java.io.File(ctx.applicationContext.filesDir, "last_crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                file.writeText(sw.toString().lines().take(25).joinToString("\n"))
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    fun showIfNeeded(activity: AppCompatActivity) {
        install(activity)
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = activity.getSharedPreferences("nitido", Context.MODE_PRIVATE)
        val am = activity.getSystemService(ActivityManager::class.java)
        val info = try { am.getHistoricalProcessExitReasons(activity.packageName, 0, 1).firstOrNull() } catch (t: Throwable) { null } ?: return
        if (info.timestamp <= prefs.getLong(KEY, 0L)) return
        prefs.edit().putLong(KEY, info.timestamp).apply()

        val why = when (info.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> activity.getString(R.string.crash_low_memory)
            ApplicationExitInfo.REASON_CRASH_NATIVE -> activity.getString(R.string.crash_native)
            ApplicationExitInfo.REASON_CRASH -> activity.getString(R.string.crash_java)
            ApplicationExitInfo.REASON_ANR -> activity.getString(R.string.crash_anr)
            ApplicationExitInfo.REASON_SIGNALED -> if (info.status == 9) activity.getString(R.string.crash_killed) else return
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> activity.getString(R.string.crash_resources)
            else -> return   // normal closes (swiped away, updated, etc.)
        }
        val time = SimpleDateFormat("d MMM HH:mm", Locale("es", "MX")).format(Date(info.timestamp))
        val details = buildString {
            append("motivo=").append(info.reason).append(" estado=").append(info.status)
            append(" memoria=").append(info.pss / 1024).append("MB")
            info.description?.let { append("\n").append(it) }
            val javaTrace = java.io.File(activity.filesDir, "last_crash.txt")
            if (info.reason == ApplicationExitInfo.REASON_CRASH && javaTrace.exists()) {
                append("\n").append(javaTrace.readText())
                javaTrace.delete()
            }
            if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || info.reason == ApplicationExitInfo.REASON_ANR) {
                try {
                    info.traceInputStream?.bufferedReader()?.use { r ->
                        val lines = r.readLines().filter { it.contains("#0") || it.contains("signal") || it.contains("Abort") || it.contains("libsdjni") || it.contains("libtensorflow") || it.contains("backtrace") }
                        append("\n").append(lines.take(15).joinToString("\n"))
                    }
                } catch (_: Throwable) { }
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.crash_title)
            .setMessage(activity.getString(R.string.crash_body, time, why, details))
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                val clip = activity.getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(android.content.ClipData.newPlainText("falla", "$why\n$details"))
                android.widget.Toast.makeText(activity, R.string.crash_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }
}
