package com.nitido.app

import android.os.SystemClock

/**
 * How busy Nítido keeps the GPU: every model call that runs on the GPU is timed. HyperOS doesn't
 * let apps read the GPU's own load, but while processing nearly all GPU work is Nítido's.
 */
object GpuMeter {
    private val lock = Any()
    private var busyMs = 0L
    private var active = 0
    private var activeSince = 0L

    inline fun <T> track(onGpu: Boolean, block: () -> T): T {
        if (!onGpu) return block()
        begin()
        try { return block() } finally { end() }
    }

    fun begin() = synchronized(lock) {
        if (active++ == 0) activeSince = SystemClock.elapsedRealtime()
    }

    fun end() = synchronized(lock) {
        if (--active == 0) busyMs += SystemClock.elapsedRealtime() - activeSince
        if (active < 0) active = 0
    }

    /** Total GPU-busy time so far, including a call still running. */
    fun busyMs(): Long = synchronized(lock) {
        busyMs + if (active > 0) SystemClock.elapsedRealtime() - activeSince else 0L
    }
}
