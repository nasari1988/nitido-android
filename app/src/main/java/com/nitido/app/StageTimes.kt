package com.nitido.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.Locale
import kotlin.math.roundToInt

/**
 * How long each restoration step takes on this phone, in seconds per megapixel of the photo,
 * learned from the restorations already done (it starts from rough guesses for a Snapdragon 8s Gen 4).
 */
object StageTimes {
    private val defaults = mapOf(
        "m" to (40f to 40f), "c" to (2f to 2f), "n" to (25f to 70f), "k" to (15f to 15f),
        "d" to (60f to 180f), "s" to (400f to 1200f), "u" to (20f to 80f), "f" to (10f to 25f),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("stage_times", Context.MODE_PRIVATE)
    private fun key(stage: String, gpu: Boolean) = "$stage-${if (gpu) "g" else "c"}"

    fun perMp(ctx: Context, stage: String, gpu: Boolean): Float {
        val d = defaults[stage] ?: (10f to 10f)
        return prefs(ctx).getFloat(key(stage, gpu), if (gpu) d.first else d.second)
    }

    fun learned(ctx: Context, stage: String, gpu: Boolean) = prefs(ctx).contains(key(stage, gpu))

    /** Remembers one measured step (seconds for [mp] megapixels), averaged with what was known. */
    fun record(ctx: Context, stage: String, gpu: Boolean, seconds: Float, mp: Float) {
        if (mp <= 0f || seconds <= 0f) return
        val v = seconds / mp
        val p = prefs(ctx)
        val old = if (p.contains(key(stage, gpu))) p.getFloat(key(stage, gpu), v) else v
        p.edit().putFloat(key(stage, gpu), old * 0.5f + v * 0.5f).apply()
    }

    /** Seconds for these steps on a photo of [mp] megapixels (enlarging counts the enlarged size for later steps). */
    fun estimate(ctx: Context, stages: List<String>, gpu: Boolean, mp: Float, scale: Int): Float {
        var s = 0f
        for (st in stages) {
            val size = if (st == "f") mp * scale * scale else mp
            s += perMp(ctx, st, gpu) * size
        }
        return s
    }

    fun format(seconds: Float): String {
        val m = (seconds / 60f)
        return if (m < 1f) "menos de 1 min" else String.format(Locale.US, "~%d min", m.roundToInt().coerceAtLeast(1))
    }

    /** Battery percent and whether it's charging. */
    fun battery(ctx: Context): Pair<Int, Boolean> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 100 to true
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return level to charging
    }
}
