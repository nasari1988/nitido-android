package com.nitido.app

import android.content.Context
import java.io.File

/**
 * An image model converted to MNN (ESRGAN-type upscalers) that takes one fixed-size
 * tile [1,3,H,W] (RGB 0..1) and returns a bigger one. Runs on the GPU (OpenCL) or CPU.
 */
class MnnTile(context: Context, asset: String, private val gpu: Boolean, precise: Boolean = false) : AutoCloseable {

    private var handle: Long
    val inShape: IntArray
    val outShape: IntArray

    init {
        SdNative.loadError?.let { throw IllegalStateException("motor no disponible", it) }
        // an absolute path is a downloaded model; anything else is an asset inside the app
        val file = if (asset.startsWith("/")) File(asset) else copyAsset(context, asset)
        handle = nOpen(file.absolutePath, gpu, context.cacheDir.absolutePath, precise)
        if (handle == 0L) throw IllegalStateException("no se pudo abrir $asset")
        inShape = nShape(handle, 0)
        outShape = nShape(handle, 1)
    }

    fun run(input: FloatArray, output: FloatArray): Boolean =
        handle != 0L && GpuMeter.track(gpu) { nRun(handle, input, output) }

    override fun close() {
        if (handle != 0L) { nClose(handle); handle = 0L }
    }

    companion object {
        /** MNN reads models from a file path, so the asset is copied out once (and again if it changes). */
        private fun copyAsset(ctx: Context, name: String): File {
            val dir = File(ctx.filesDir, "upscalers").apply { mkdirs() }
            val f = File(dir, name)
            val size = ctx.assets.openFd(name).use { it.length }
            if (!f.exists() || f.length() != size) {
                val tmp = File(dir, "$name.tmp")
                ctx.assets.open(name).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                tmp.renameTo(f)
            }
            return f
        }

        fun hasAsset(ctx: Context, name: String) =
            try { ctx.assets.openFd(name).close(); true } catch (_: Throwable) { false }

        @JvmStatic private external fun nOpen(path: String, gpu: Boolean, cacheDir: String, precise: Boolean): Long
        @JvmStatic private external fun nShape(handle: Long, which: Int): IntArray
        @JvmStatic private external fun nRun(handle: Long, input: FloatArray, output: FloatArray): Boolean
        @JvmStatic private external fun nClose(handle: Long)
    }
}
