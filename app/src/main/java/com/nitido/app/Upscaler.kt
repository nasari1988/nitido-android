package com.nitido.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

enum class Engine(val label: String) { GPU("GPU"), NPU("NPU"), CPU("CPU") }

/**
 * Real-ESRGAN General x4v3 running on-device with TensorFlow Lite.
 * The model has a fixed input size (e.g. 128×128), so images are processed
 * in overlapping tiles and stitched back together.
 *
 * Must be created and used on the same thread (required by the GPU delegate).
 */
class Upscaler(
    private val context: Context,
    requested: Engine,
    private val modelFile: String = MODEL_FILE,
    private val pad: Int = 8,
    private val precise: Boolean = false,
) : AutoCloseable {

    private var gpu: GpuDelegate? = null
    private var nnapi: NnApiDelegate? = null
    /** Called after each finished tile with the picture being built (for a live preview). */
    var onTile: ((Bitmap) -> Unit)? = null
    private var interpreter: Interpreter? = null
    private var mnn: MnnTile? = null
    private var mnnIn: FloatArray? = null
    val engine: Engine

    private val nchw: Boolean
    private val inH: Int
    private val inW: Int
    private val outH: Int
    private val outW: Int
    val factor: Int

    private val inBuf: ByteBuffer
    private val inF: FloatBuffer
    private val outBuf: ByteBuffer
    private val outArr: FloatArray

    init {
        val inShape: IntArray
        val outShape: IntArray
        if (modelFile.endsWith(".mnn")) {
            // community ESRGAN models (UltraSharp, Siax, Anime) run on the MNN engine
            val wantGpu = requested != Engine.CPU
            var used = if (wantGpu) Engine.GPU else Engine.CPU
            val m = try { MnnTile(context, modelFile, wantGpu, precise) } catch (t: Throwable) {
                if (!wantGpu) throw t
                used = Engine.CPU
                MnnTile(context, modelFile, false, precise)
            }
            mnn = m
            engine = used
            inShape = m.inShape
            outShape = m.outShape
        } else {
            val model = loadModel(context)
            var used = requested
            val interp = try {
                build(model, requested)
            } catch (t: Throwable) {
                closeDelegates()
                used = Engine.CPU
                build(model, Engine.CPU)
            }
            interpreter = interp
            engine = used
            inShape = interp.getInputTensor(0).shape()
            outShape = interp.getOutputTensor(0).shape()
        }
        nchw = inShape[1] == 3
        if (nchw) { inH = inShape[2]; inW = inShape[3]; outH = outShape[2]; outW = outShape[3] }
        else { inH = inShape[1]; inW = inShape[2]; outH = outShape[1]; outW = outShape[2] }
        factor = outH / inH

        inBuf = ByteBuffer.allocateDirect(inH * inW * 3 * 4).order(ByteOrder.nativeOrder())
        inF = inBuf.asFloatBuffer()
        outBuf = ByteBuffer.allocateDirect(outH * outW * 3 * 4).order(ByteOrder.nativeOrder())
        outArr = FloatArray(outH * outW * 3)
        if (mnn != null) mnnIn = FloatArray(inH * inW * 3)
    }

    private fun build(model: MappedByteBuffer, e: Engine): Interpreter {
        val opts = Interpreter.Options()
        when (e) {
            Engine.GPU -> {
                val d = GpuDelegate()
                gpu = d
                opts.addDelegate(d)
            }
            Engine.NPU -> {
                val o = NnApiDelegate.Options()
                    .setAllowFp16(true)
                    .setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                val d = NnApiDelegate(o)
                nnapi = d
                opts.addDelegate(d)
            }
            Engine.CPU -> opts.setNumThreads(4)
        }
        val interp = Interpreter(model, opts)
        // Run once so a delegate that cannot handle the model fails here, not mid-image.
        val shape = interp.getInputTensor(0).shape()
        val outShape = interp.getOutputTensor(0).shape()
        val i = ByteBuffer.allocateDirect(shape.fold(4) { a, b -> a * b }).order(ByteOrder.nativeOrder())
        val o = ByteBuffer.allocateDirect(outShape.fold(4) { a, b -> a * b }).order(ByteOrder.nativeOrder())
        interp.run(i, o)
        return interp
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        context.assets.openFd(modelFile).use { fd ->
            FileInputStream(fd.fileDescriptor).use { fis ->
                return fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    /**
     * Run the model over [src] in tiles. [scale] is the output scale (1, 2 or 4) and must
     * divide the model's own factor; extra resolution is averaged down.
     */
    fun upscale(src: Bitmap, scale: Int, onProgress: (Float) -> Unit, cancelled: () -> Boolean): Bitmap? {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val f = factor
        val div = f / scale
        val PAD = pad
        val stepW = inW - 2 * PAD
        val stepH = inH - 2 * PAD
        val cols = (w + stepW - 1) / stepW
        val rows = (h + stepH - 1) / stepH
        val total = cols * rows
        val out = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888)
        val tile = IntArray((stepW * scale) * (stepH * scale))
        val plane = outH * outW
        var done = 0
        // 1× models (grain, blur): blend the overlapping borders of neighbouring blocks with soft
        // weights, so no block edge can show as a straight line in the photo.
        val blend = f == 1 && scale == 1 && PAD >= 8
        val acc = if (blend) FloatArray(w * h * 3) else null
        val wsum = if (blend) FloatArray(w * h) else null

        for (r in 0 until rows) for (c in 0 until cols) {
            if (cancelled()) return null
            val x0 = c * stepW
            val y0 = r * stepH
            val cw = minOf(stepW, w - x0)
            val ch = minOf(stepH, h - y0)

            // Fill the input tile, clamping coordinates at the image border.
            val inPlane = inH * inW
            for (y in 0 until inH) {
                val sy = (y0 - PAD + y).coerceIn(0, h - 1)
                for (x in 0 until inW) {
                    val sx = (x0 - PAD + x).coerceIn(0, w - 1)
                    val p = px[sy * w + sx]
                    val rr = Color.red(p) / 255f
                    val gg = Color.green(p) / 255f
                    val bb = Color.blue(p) / 255f
                    if (nchw) {
                        val i = y * inW + x
                        inF.put(i, rr); inF.put(inPlane + i, gg); inF.put(2 * inPlane + i, bb)
                    } else {
                        val i = (y * inW + x) * 3
                        inF.put(i, rr); inF.put(i + 1, gg); inF.put(i + 2, bb)
                    }
                }
            }

            runTile(cpu = false)
            // The phone's GPU sometimes computes a tile wrong (a patch of colored grid).
            // Check it against the input and redo that tile on the CPU if it looks broken.
            if (looksBroken()) {
                if (engine != Engine.CPU) {
                    android.util.Log.w("Nitido", "tile $c,$r broken on GPU, redoing on CPU")
                    runTile(cpu = true)
                }
                // Some models fail on certain content (very dark, saturated areas) even on the
                // CPU. Then this block keeps the original pixels (just enlarged) instead of garbage.
                if (looksBroken()) {
                    android.util.Log.w("Nitido", "tile $c,$r broken, keeping the original pixels")
                    passThrough()
                }
            }

            // Copy the tile's core (without padding) into the result.
            val tw = cw * scale
            val th = ch * scale
            for (ty in 0 until th) {
                for (tx in 0 until tw) {
                    var rs = 0f; var gs = 0f; var bs = 0f
                    for (dy in 0 until div) for (dx in 0 until div) {
                        val oy = PAD * f + ty * div + dy
                        val ox = PAD * f + tx * div + dx
                        if (nchw) {
                            val i = oy * outW + ox
                            rs += outArr[i]; gs += outArr[plane + i]; bs += outArr[2 * plane + i]
                        } else {
                            val i = (oy * outW + ox) * 3
                            rs += outArr[i]; gs += outArr[i + 1]; bs += outArr[i + 2]
                        }
                    }
                    val n = (div * div).toFloat()
                    tile[ty * tw + tx] = Color.rgb(to8(rs / n), to8(gs / n), to8(bs / n))
                }
            }
            out.setPixels(tile, 0, tw, x0 * scale, y0 * scale, tw, th)
            if (acc != null && wsum != null) {
                for (y in 0 until inH) {
                    val sy = y0 - PAD + y
                    if (sy < 0 || sy >= h) continue
                    for (x in 0 until inW) {
                        val sx = x0 - PAD + x
                        if (sx < 0 || sx >= w) continue
                        val d = minOf(minOf(x, inW - 1 - x), minOf(y, inH - 1 - y))
                        val wt = ((d + 1f) / PAD).coerceIn(0.02f, 1f)
                        val i = sy * w + sx
                        val o = y * outW + x
                        val rr: Float; val gg: Float; val bb: Float
                        if (nchw) { rr = outArr[o]; gg = outArr[plane + o]; bb = outArr[2 * plane + o] }
                        else { rr = outArr[o * 3]; gg = outArr[o * 3 + 1]; bb = outArr[o * 3 + 2] }
                        acc[i * 3] += rr * wt; acc[i * 3 + 1] += gg * wt; acc[i * 3 + 2] += bb * wt
                        wsum[i] += wt
                    }
                }
            }
            onTile?.invoke(out)

            done++
            onProgress(done.toFloat() / total)
        }
        if (acc != null && wsum != null) {
            val row = IntArray(w)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val s = wsum[i]
                    row[x] = if (s <= 0f) px[i] else Color.rgb(to8(acc[i * 3] / s), to8(acc[i * 3 + 1] / s), to8(acc[i * 3 + 2] / s))
                }
                out.setPixels(row, 0, w, 0, y, w, 1)
            }
        }
        return out
    }

    private var cpuMnn: MnnTile? = null
    private var cpuInterp: Interpreter? = null

    private fun runTile(cpu: Boolean) {
        val m = if (cpu && mnn != null) (cpuMnn ?: MnnTile(context, modelFile, false, precise).also { cpuMnn = it }) else mnn
        if (m != null) {
            val arr = mnnIn!!
            inF.rewind(); inF.get(arr)
            if (!m.run(arr, outArr)) throw IllegalStateException("upscaler run failed")
        } else {
            val interp = if (cpu) (cpuInterp ?: Interpreter(loadModel(context), Interpreter.Options().setNumThreads(4)).also { cpuInterp = it })
                         else interpreter!!
            inBuf.rewind()
            outBuf.rewind()
            GpuMeter.track(!cpu && engine == Engine.GPU) { interp.run(inBuf, outBuf) }
            outBuf.rewind()
            outBuf.asFloatBuffer().get(outArr)
        }
    }

    /**
     * True when the output tile clearly isn't a cleaned-up version of the input: values far
     * outside 0..1, NaN, or many pixels whose color is very different from the input pixel.
     */
    private fun looksBroken(): Boolean {
        val f = factor
        val plane = outH * outW
        val inPlane = inH * inW
        var bad = 0; var n = 0
        val sumO = DoubleArray(3); val sumI = DoubleArray(3)
        var y = 0
        while (y < outH) {
            var x = 0
            while (x < outW) {
                val iy = minOf(y / f, inH - 1); val ix = minOf(x / f, inW - 1)
                for (ch in 0 until 3) {
                    val o = if (nchw) outArr[ch * plane + y * outW + x] else outArr[(y * outW + x) * 3 + ch]
                    if (o.isNaN() || o < -0.3f || o > 1.3f) return true
                    val i = if (nchw) inF.get(ch * inPlane + iy * inW + ix) else inF.get((iy * inW + ix) * 3 + ch)
                    sumO[ch] = sumO[ch] + o; sumI[ch] = sumI[ch] + i
                    if (kotlin.math.abs(o - i) > 0.4f) bad++
                }
                n++
                x += 2
            }
            y += 2
        }
        if (bad > n * 3 / 100) return true   // many pixels far from the input
        // A cleaned-up or enlarged block keeps the same average color. A GPU block that came out
        // slightly lighter or tinted shows up as a visible rectangle, so that counts as broken too.
        for (ch in 0 until 3) if (kotlin.math.abs(sumO[ch] - sumI[ch]) / n > 0.02) return true
        return false
    }

    /** Fills the output tile with the input tile, enlarged smoothly (bilinear). */
    private fun passThrough() {
        val f = factor.toFloat()
        val plane = outH * outW
        val inPlane = inH * inW
        for (y in 0 until outH) {
            val fy = ((y + 0.5f) / f - 0.5f).coerceIn(0f, inH - 1f)
            val y0 = fy.toInt(); val y1 = minOf(y0 + 1, inH - 1); val ty = fy - y0
            for (x in 0 until outW) {
                val fx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, inW - 1f)
                val x0 = fx.toInt(); val x1 = minOf(x0 + 1, inW - 1); val tx = fx - x0
                for (ch in 0 until 3) {
                    fun at(yy: Int, xx: Int) = if (nchw) inF.get(ch * inPlane + yy * inW + xx) else inF.get((yy * inW + xx) * 3 + ch)
                    val v = (at(y0, x0) * (1 - tx) + at(y0, x1) * tx) * (1 - ty) + (at(y1, x0) * (1 - tx) + at(y1, x1) * tx) * ty
                    if (nchw) outArr[ch * plane + y * outW + x] = v else outArr[(y * outW + x) * 3 + ch] = v
                }
            }
        }
    }

    private fun to8(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun closeDelegates() {
        gpu?.close(); gpu = null
        nnapi?.close(); nnapi = null
    }

    override fun close() {
        interpreter?.close(); interpreter = null
        cpuInterp?.close(); cpuInterp = null
        cpuMnn?.close(); cpuMnn = null
        mnn?.close(); mnn = null
        closeDelegates()
    }

    companion object {
        const val MODEL_FILE = "realesrgan_x4.tflite"
        const val MODEL_X4PLUS = "realesrgan_x4plus.tflite"
        const val MODEL_DEBLUR = "nafnet_deblur.tflite"
        const val MODEL_ULTRASHARP = "up_ultrasharp.mnn"
        const val MODEL_SIAX = "up_siax.mnn"
        const val MODEL_ANIME = "up_anime.mnn"
    }
}
