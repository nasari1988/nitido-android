package com.nitido.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The screen shown while something is being processed (Crear, Mejorar, Restaurar): only the photo
 * being worked on, live, the progress of the current step and of the whole job, the time left,
 * and a monitor of memory, CPU, GPU and temperature. Laid over the activity's own screen.
 */
class ProcessScreen(private val activity: Activity, onStop: () -> Unit) {

    private val root: View = activity.layoutInflater.inflate(R.layout.view_process, null)
    private val title: TextView = root.findViewById(R.id.procTitle)
    private val preview: ImageView = root.findViewById(R.id.procPreview)
    private val piece: TextView = root.findViewById(R.id.procPiece)
    private val step: TextView = root.findViewById(R.id.procStep)
    private val stepCount: TextView = root.findViewById(R.id.procStepCount)
    private val barStep: LinearProgressIndicator = root.findViewById(R.id.procBarStep)
    private val barTotal: LinearProgressIndicator = root.findViewById(R.id.procBarTotal)
    private val stepPct: TextView = root.findViewById(R.id.procStepPct)
    private val totalPct: TextView = root.findViewById(R.id.procTotalPct)
    private val eta: TextView = root.findViewById(R.id.procEta)

    private var t0 = 0L
    private var lastPreview = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val stats = SystemStats(activity.applicationContext)
    private val tick = object : Runnable {
        override fun run() {
            if (!showing) return
            showStats(stats.read())
            handler.postDelayed(this, 1500)
        }
    }

    var showing = false
        private set

    init {
        activity.addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.findViewById<View>(R.id.procStop).setOnClickListener { onStop() }
    }

    /** Opens the screen. [startedAt] (elapsedRealtime) lets a rebuilt screen keep the same clock. */
    fun show(titleText: String, photo: Bitmap?, startedAt: Long = SystemClock.elapsedRealtime()) {
        title.text = titleText
        if (photo != null) preview.setImageBitmap(photo)
        if (!showing) {
            t0 = startedAt
            showing = true
            root.visibility = View.VISIBLE
            stepCount.text = ""
            piece.visibility = View.GONE
            update("", 0, 1, 0f, 0f)
            handler.post(tick)
        }
    }

    fun hide() {
        showing = false
        handler.removeCallbacks(tick)
        root.visibility = View.GONE
    }

    /** Safe from any thread. [stepFrac] and [totalFrac] are 0..1; [stepIndex] counts from 0. */
    fun update(stepName: String, stepIndex: Int, steps: Int, stepFrac: Float, totalFrac: Float, pieceText: String? = null,
               etaSec: Float? = null) {
        activity.runOnUiThread {
            if (!showing) return@runOnUiThread
            step.text = stepName
            stepCount.text = if (steps > 1) activity.getString(R.string.proc_step_of, stepIndex + 1, steps) else ""
            barStep.setProgressCompat((stepFrac.coerceIn(0f, 1f) * 1000).roundToInt(), true)
            barTotal.setProgressCompat((totalFrac.coerceIn(0f, 1f) * 1000).roundToInt(), true)
            stepPct.text = String.format(Locale.US, "%d %%", (stepFrac * 100).roundToInt().coerceIn(0, 100))
            totalPct.text = String.format(Locale.US, "%d %%", (totalFrac * 100).roundToInt().coerceIn(0, 100))
            val el = (SystemClock.elapsedRealtime() - t0) / 1000f
            // With an estimate from the steps' known times use it; otherwise wait until the
            // progress says something (quick first steps would make it look almost done).
            val left = etaSec ?: if (totalFrac >= 0.1f && el >= 10f) (el / totalFrac) - el else null
            eta.text = left?.let { formatLeft(it) } ?: activity.getString(R.string.proc_eta_wait)
            if (pieceText != null) { piece.text = pieceText; piece.visibility = View.VISIBLE } else piece.visibility = View.GONE
        }
    }

    private fun formatLeft(sec: Float): String {
        val s = sec.roundToInt().coerceAtLeast(0)
        return when {
            s >= 600 -> "${(s + 30) / 60} min"
            s >= 60 -> "${s / 60} min ${s % 60} s"
            else -> "$s s"
        }
    }

    /** Shows the image being worked on. Throttled, and copied small, so it's cheap from a worker thread. */
    fun preview(bmp: Bitmap, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPreview < 1200) return
        lastPreview = now
        val long = maxOf(bmp.width, bmp.height)
        val small = try {
            if (long > 1280) Bitmap.createScaledBitmap(bmp, bmp.width * 1280 / long, bmp.height * 1280 / long, true)
            else bmp.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) { return }
        activity.runOnUiThread { if (showing) preview.setImageBitmap(small) }
    }

    private fun showStats(s: SystemStats.Snapshot) {
        fun set(valueId: Int, barId: Int, noteId: Int, value: String, pct: Int?, note: String) {
            root.findViewById<TextView>(valueId).text = value
            root.findViewById<ProgressBar>(barId).progress = pct ?: 0
            root.findViewById<TextView>(noteId).text = note
        }
        val gb = { b: Long -> String.format(Locale.US, "%.1f", b / 1e9) }
        set(R.id.monMem, R.id.monMemBar, R.id.monMemNote, "${gb(s.memUsed)} / ${gb(s.memTotal)} GB",
            (s.memUsed * 100 / s.memTotal.coerceAtLeast(1)).toInt(), activity.getString(R.string.proc_mem_app, gb(s.appMem)))
        set(R.id.monCpu, R.id.monCpuBar, R.id.monCpuNote, s.cpuPct?.let { "$it %" } ?: "—", s.cpuPct,
            if (s.cpuGhz != null) activity.getString(R.string.proc_cpu_note, s.cores, String.format(Locale.US, "%.1f", s.cpuGhz))
            else activity.getString(R.string.proc_cpu_cores, s.cores))
        val watts = s.gpuWatts?.let { " · " + String.format(Locale.US, "%.1f W", it) } ?: ""
        set(R.id.monGpu, R.id.monGpuBar, R.id.monGpuNote, s.gpuPct?.let { "$it %" } ?: "—", s.gpuPct,
            when {
                s.gpuPct == null -> activity.getString(R.string.proc_unavailable)
                s.gpuEstimated -> activity.getString(R.string.proc_gpu_estimated) + watts
                else -> activity.getString(R.string.proc_gpu_note) + watts
            })
        val t = s.cpuTemp ?: s.batteryTemp
        set(R.id.monTemp, R.id.monTempBar, R.id.monTempNote, t?.let { String.format(Locale.US, "%.0f °C", it) } ?: "—",
            t?.let { ((it - 20f) / 30f * 100).roundToInt().coerceIn(0, 100) },
            activity.getString(if (s.cpuTemp != null) R.string.proc_temp_cpu else R.string.proc_temp_battery,
                activity.getString(s.thermalText)))
    }
}

/** Reads what Android lets an app read about the phone's load. Anything blocked comes back null. */
class SystemStats(private val ctx: Context) {

    class Snapshot(
        val memUsed: Long, val memTotal: Long, val appMem: Long,
        val cpuPct: Int?, val cores: Int, val cpuGhz: Float?,
        val gpuPct: Int?, val batteryTemp: Float?, val cpuTemp: Float?, val thermalText: Int,
        val gpuEstimated: Boolean = false, val gpuWatts: Float? = null,
    )

    private var lastCpuTicks = -1L
    private var lastWall = 0L
    private val cores = Runtime.getRuntime().availableProcessors()
    private val cpuZone: File? by lazy { findCpuZone() }
    private var lastBusy = -1L
    private var lastBusyWall = 0L
    private val power = GpuPower(ctx)

    fun read(): Snapshot {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val app = try { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss * 1024L } catch (t: Throwable) { 0L }
        val real = gpu()
        val est = gpuEstimate()
        return Snapshot(mi.totalMem - mi.availMem, mi.totalMem, app, cpu(), cores, freqGhz(), real ?: est,
            battery(), cpuZone?.let { readTemp(it) }, thermal(), gpuEstimated = real == null && est != null,
            gpuWatts = power.watts())
    }

    /** Share of the last interval the GPU spent on Nítido's model calls. */
    private fun gpuEstimate(): Int? {
        val busy = GpuMeter.busyMs(); val now = SystemClock.elapsedRealtime()
        val out = if (lastBusy >= 0 && now > lastBusyWall)
            ((busy - lastBusy) * 100 / (now - lastBusyWall)).toInt().coerceIn(0, 100) else null
        lastBusy = busy; lastBusyWall = now
        return out
    }

    /** This app's share of all cores, from /proc/self/stat (user + system time). */
    private fun cpu(): Int? = try {
        val f = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(" ")
        val ticks = f[11].toLong() + f[12].toLong()   // utime, stime (after the name field)
        val now = SystemClock.elapsedRealtime()
        val out = if (lastCpuTicks >= 0 && now > lastWall) {
            val used = (ticks - lastCpuTicks) * 10.0           // 100 ticks per second → ms
            (used / ((now - lastWall) * cores) * 100).roundToInt().coerceIn(0, 100)
        } else null
        lastCpuTicks = ticks; lastWall = now
        out
    } catch (t: Throwable) { null }

    private fun freqGhz(): Float? {
        var best = 0L
        for (i in 0 until cores) {
            val v = try { File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong() } catch (t: Throwable) { 0L }
            if (v > best) best = v
        }
        return if (best > 0) best / 1e6f else null
    }

    /** Adreno load, if HyperOS lets apps read it. */
    private fun gpu(): Int? {
        try {
            val p = File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").readText().trim().removeSuffix("%").trim()
            p.toIntOrNull()?.let { return it.coerceIn(0, 100) }
        } catch (_: Throwable) { }
        try {
            val (busy, total) = File("/sys/class/kgsl/kgsl-3d0/gpubusy").readText().trim().split(Regex("\\s+")).map { it.toLong() }
            if (total > 0) return (busy * 100 / total).toInt().coerceIn(0, 100)
        } catch (_: Throwable) { }
        return null
    }

    private fun battery(): Float? = try {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
    } catch (t: Throwable) { null }

    private fun findCpuZone(): File? = try {
        File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }?.firstOrNull { z ->
            val type = try { File(z, "type").readText().lowercase() } catch (t: Throwable) { "" }
            (type.contains("cpu") || type.contains("tsens")) && readTemp(File(z, "temp")) != null
        }?.let { File(it, "temp") }
    } catch (t: Throwable) { null }

    private fun readTemp(f: File): Float? = try {
        val v = f.readText().trim().toFloat()
        val c = if (v > 1000) v / 1000f else v
        c.takeIf { it in 5f..120f }
    } catch (t: Throwable) { null }

    private fun thermal(): Int {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> R.string.proc_thermal_ok
            PowerManager.THERMAL_STATUS_LIGHT -> R.string.proc_thermal_warm
            PowerManager.THERMAL_STATUS_MODERATE -> R.string.proc_thermal_hot
            else -> R.string.proc_thermal_very_hot
        }
    }
}

/**
 * GPU power from Android 15's power monitors (energy counters per part of the chip), when the
 * phone offers one for the GPU. Called through reflection: the app is built against Android 14.
 */
class GpuPower(ctx: Context) {
    @Volatile private var monitor: Any? = null
    @Volatile private var watts: Float? = null
    private var lastEnergy = -1L
    private var lastTime = 0L
    private val shm: Any? = if (android.os.Build.VERSION.SDK_INT >= 35)
        try { ctx.getSystemService(Context.SYSTEM_HEALTH_SERVICE) } catch (t: Throwable) { null } else null
    private val exec = java.util.concurrent.Executor { it.run() }

    init {
        try {
            shm?.javaClass?.getMethod("getSupportedPowerMonitors", java.util.concurrent.Executor::class.java,
                java.util.function.Consumer::class.java)?.invoke(shm, exec, java.util.function.Consumer<List<Any>> { list ->
                monitor = list.firstOrNull { m ->
                    val n = try { m.javaClass.getMethod("getName").invoke(m) as String } catch (t: Throwable) { "" }
                    n.contains("gpu", true) || n.contains("gfx", true)
                }
            })
        } catch (t: Throwable) { }
    }

    /** Latest GPU power in watts, or null when the phone has no GPU power monitor. */
    fun watts(): Float? {
        val m = monitor ?: return null
        try {
            shm!!.javaClass.getMethod("getPowerMonitorReadings", List::class.java, java.util.concurrent.Executor::class.java,
                android.os.OutcomeReceiver::class.java).invoke(shm, listOf(m), exec,
                object : android.os.OutcomeReceiver<Any, RuntimeException> {
                    override fun onResult(r: Any) {
                        try {
                            val pmClass = m.javaClass
                            val e = r.javaClass.getMethod("getConsumedEnergy", pmClass).invoke(r, m) as Long      // µW·s
                            val t = r.javaClass.getMethod("getTimestampMillis", pmClass).invoke(r, m) as Long
                            if (lastEnergy >= 0 && t > lastTime) watts = ((e - lastEnergy) / 1e6f) / ((t - lastTime) / 1000f)
                            lastEnergy = e; lastTime = t
                        } catch (t: Throwable) { }
                    }
                    override fun onError(error: RuntimeException) {}
                })
        } catch (t: Throwable) { return null }
        return watts?.takeIf { it in 0f..30f }
    }
}
