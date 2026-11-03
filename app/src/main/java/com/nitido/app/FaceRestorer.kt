package com.nitido.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Face restoration with GFPGAN v1.4 (LiteRT build, Apache-2.0).
 * Faces are found with ML Kit (on-device), aligned to the FFHQ 512 template from their
 * five landmarks, restored, and blended back with a soft edge.
 *
 * Create and use on one thread (GPU delegate requirement).
 */
class FaceRestorer(
    context: Context,
    useGpu: Boolean,
    /** Optional CodeFormer (restoration pack): better on very old or damaged faces. */
    codeFormer: File? = null,
    /** CodeFormer only: 0 = sharpest (may change features), 1 = most faithful to the original face. */
    private val fidelity: Float = 0.7f,
) : AutoCloseable {

    private var gpu: GpuDelegate? = null
    private var interpreter: Interpreter? = null
    private var cf: MnnTile? = null
    val usedGpu: Boolean
    private val inBuf = ByteBuffer.allocateDirect(3 * N * 4).order(ByteOrder.nativeOrder())
    private val outBuf = ByteBuffer.allocateDirect(3 * N * 4).order(ByteOrder.nativeOrder())

    init {
        var gpuOk = false
        if (codeFormer != null) {
            cf = try { MnnTile(context, codeFormer.absolutePath, useGpu, precise = true).also { gpuOk = useGpu } }
                 catch (t: Throwable) { if (!useGpu) throw t; MnnTile(context, codeFormer.absolutePath, false, precise = true) }
        } else interpreter = if (useGpu) {
            val file = modelFile(context)
            try {
                val d = GpuDelegate()
                gpu = d
                Interpreter(file, Interpreter.Options().addDelegate(d)).also { gpuOk = true }
            } catch (t: Throwable) {
                gpu?.close(); gpu = null
                Interpreter(file, Interpreter.Options().setNumThreads(4))
            }
        } else {
            Interpreter(modelFile(context), Interpreter.Options().setNumThreads(4))
        }
        usedGpu = gpuOk
    }

    /**
     * Restores every face in [image] in place. Returns how many faces were restored.
     * [from] (optional, same picture at any size): where the faces are read from, e.g. a cleaner
     * version from before sharpening, so its grain isn't carried into the restored faces.
     */
    fun restoreAll(image: Bitmap, onProgress: (Int, Int) -> Unit, cancelled: () -> Boolean, from: Bitmap? = null): Int {
        val src = from ?: image
        val ratio = image.width.toFloat() / src.width
        val faces = detect(src)
        var done = 0
        for ((i, pts) in faces.withIndex()) {
            if (cancelled()) break
            onProgress(i, faces.size)
            restoreOne(image, Array(5) { PointF(pts[it].x * ratio, pts[it].y * ratio) }, src, ratio)
            done++
        }
        onProgress(faces.size, faces.size)
        return done
    }

    /** Five landmarks per face in image order: eye left, eye right, nose, mouth left, mouth right. */
    private fun detect(image: Bitmap): List<Array<PointF>> {
        // ML Kit is fast enough on a downscaled copy; landmarks are scaled back up.
        val long = maxOf(image.width, image.height)
        val k = if (long > 1600) 1600f / long else 1f
        val small = if (k < 1f) Bitmap.createScaledBitmap(image, (image.width * k).toInt(), (image.height * k).toInt(), true) else image
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.05f)
                .build()
        )
        try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(small, 0)))
            return faces.mapNotNull { f ->
                val l = listOf(FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE, FaceLandmark.NOSE_BASE,
                    FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT).map { f.getLandmark(it)?.position }
                if (l.any { it == null }) return@mapNotNull null
                val p = l.map { PointF(it!!.x / k, it.y / k) }
                // Order by position in the picture, independent of which side ML Kit calls "left".
                val eyes = listOf(p[0], p[1]).sortedBy { it.x }
                val mouth = listOf(p[3], p[4]).sortedBy { it.x }
                arrayOf(eyes[0], eyes[1], p[2], mouth[0], mouth[1])
            }
        } finally {
            detector.close()
            if (small !== image) small.recycle()
        }
    }

    private fun restoreOne(image: Bitmap, pts: Array<PointF>, src: Bitmap, ratio: Float) {
        // image -> template similarity transform (least squares)
        val m = similarity(pts, TEMPLATE)
        // the face is read from [src]: its pixels map to image pixels by [ratio]
        val mSrc = Matrix(m).apply { preScale(ratio, ratio) }
        val face = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        Canvas(face).apply {
            drawColor(Color.BLACK)
            drawBitmap(src, mSrc, Paint(Paint.FILTER_BITMAP_FLAG))
        }

        val px = IntArray(N)
        face.getPixels(px, 0, S, 0, 0, S, S)
        val inF = inBuf.asFloatBuffer()
        for (i in 0 until N) {
            val c = px[i]
            inF.put(i, Color.red(c) / 127.5f - 1f)
            inF.put(N + i, Color.green(c) / 127.5f - 1f)
            inF.put(2 * N + i, Color.blue(c) / 127.5f - 1f)
        }
        val o = FloatArray(3 * N)
        val c = cf
        if (c != null) {
            // CodeFormer: RGB plus a 4th channel holding the fidelity weight
            val x = FloatArray(4 * N)
            inF.rewind(); inF.get(x, 0, 3 * N)
            java.util.Arrays.fill(x, 3 * N, 4 * N, fidelity.coerceIn(0f, 1f))
            if (!c.run(x, o)) throw IllegalStateException("CodeFormer failed")
        } else {
            inBuf.rewind(); outBuf.rewind()
            GpuMeter.track(usedGpu) { interpreter!!.run(inBuf, outBuf) }
            outBuf.rewind()
            outBuf.asFloatBuffer().get(o)
        }

        // CodeFormer/GFPGAN return the face in their own tone (often orange or pale): give it back
        // the original face's average color, measured on the middle of the face.
        run {
            val m = FloatArray(6); var cnt = 0
            for (y in 160 until 400) for (x in 150 until 362) {
                val i = y * S + x
                val c = px[i]
                if (c and 0xFFFFFF == 0) continue   // outside the photo
                m[0] += Color.red(c) / 127.5f - 1f; m[1] += Color.green(c) / 127.5f - 1f; m[2] += Color.blue(c) / 127.5f - 1f
                m[3] += o[i]; m[4] += o[N + i]; m[5] += o[2 * N + i]
                cnt++
            }
            if (cnt > 1000) for (ch in 0 until 3) {
                val shift = (m[ch] - m[3 + ch]) / cnt
                for (i in 0 until N) o[ch * N + i] += shift
            }
        }

        // Keep the photo's own colors: from the restored face take only the brightness (shape,
        // texture, light and shadow) and from the original the color, so eyes, lips and skin keep
        // their real color instead of the one the model imagined (e.g. light eyes).
        // The broad brightness also comes from the photo (fine detail stays from the model), so
        // dark eyes stay dark: y = restored + (blur(original) - blur(restored)) * LOW_KEEP.
        val yo = FloatArray(N); val yr = FloatArray(N)
        for (i in 0 until N) {
            val c = px[i]
            yo[i] = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
            yr[i] = (0.299f * (o[i] + 1f) + 0.587f * (o[N + i] + 1f) + 0.114f * (o[2 * N + i] + 1f)) / 2f
        }
        val lo = blur(yo); val lr = blur(yr)
        val (dCb, dCr) = neckShift(src, mSrc, px)
        for (i in 0 until N) {
            val c = px[i]
            if (c and 0xFFFFFF == 0) continue
            val r0 = Color.red(c) / 255f; val g0 = Color.green(c) / 255f; val b0 = Color.blue(c) / 255f
            // skin tone pulled part of the way toward the neck's (faces often come out warmer)
            val cb = -0.168736f * r0 - 0.331264f * g0 + 0.5f * b0 + dCb
            val cr = 0.5f * r0 - 0.418688f * g0 - 0.081312f * b0 + dCr
            // around the eyes the original's brightness counts much more, so dark eyes stay dark
            val keep = LOW_KEEP + (EYE_KEEP - LOW_KEEP) * eyeWeight(i % S, i / S)
            val y = yr[i] + (lo[i] - lr[i]) * keep
            o[i] = (y + 1.402f * cr) * 2f - 1f
            o[N + i] = (y - 0.344136f * cb - 0.714136f * cr) * 2f - 1f
            o[2 * N + i] = (y + 1.772f * cb) * 2f - 1f
        }

        // Restored face with a feathered alpha edge so it blends into the photo.
        for (y in 0 until S) for (x in 0 until S) {
            val i = y * S + x
            // Only the face itself (an oval over forehead, eyes, cheeks, mouth and chin) is pasted:
            // pasting the whole square also pasted a second, sharper copy of the hair and ears
            // next to the blurry original ones, which looked like a double outline or a shadow.
            val dx = (x - OVAL_CX) / OVAL_RX
            val dy = (y - OVAL_CY) / OVAL_RY
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            val t = ((1f - d) / OVAL_FEATHER).coerceIn(0f, 1f)
            val oval = t * t * (3f - 2f * t)   // smooth step
            val edge = min(min(x, S - 1 - x), min(y, S - 1 - y)).toFloat()
            val a = minOf(oval, ((edge - 8f) / FEATHER).coerceIn(0f, 1f))
            px[i] = Color.argb((a * 255).toInt(), to8(o[i]), to8(o[N + i]), to8(o[2 * N + i]))
        }
        face.setPixels(px, 0, S, 0, 0, S, S)

        val inv = Matrix()
        m.invert(inv)
        Canvas(image).drawBitmap(face, inv, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        face.recycle()
    }

    /** 0..1: how close a template pixel is to either eye (soft ovals around both eyes). */
    private fun eyeWeight(x: Int, y: Int): Float {
        var w = 0f
        for (e in 0..1) {
            val dx = (x - TEMPLATE[e].x) / 44f
            val dy = (y - TEMPLATE[e].y) / 28f
            val t = ((1f - kotlin.math.sqrt(dx * dx + dy * dy)) / 0.5f).coerceIn(0f, 1f)
            w = maxOf(w, t * t * (3f - 2f * t))
        }
        return w
    }

    /**
     * Chroma (Cb, Cr) to add to the face so its skin tone moves halfway toward the neck's, read
     * from the photo just below the chin. Zero when the neck isn't visible or isn't skin-colored.
     */
    private fun neckShift(src: Bitmap, mSrc: Matrix, facePx: IntArray): Pair<Float, Float> {
        val inv = Matrix(); if (!mSrc.invert(inv)) return 0f to 0f
        var nb = 0.0; var nr = 0.0; var nn = 0
        val pt = FloatArray(2)
        var ty = 468f
        while (ty < 590f) {
            var tx = 206f
            while (tx < 306f) {
                pt[0] = tx; pt[1] = ty; inv.mapPoints(pt)
                val x = pt[0].toInt(); val y = pt[1].toInt()
                if (x in 0 until src.width && y in 0 until src.height) {
                    val c = src.getPixel(x, y)
                    val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
                    val yy = 0.299f * r + 0.587f * g + 0.114f * b
                    val cb = -0.168736f * r - 0.331264f * g + 0.5f * b
                    val cr = 0.5f * r - 0.418688f * g - 0.081312f * b
                    if (yy in 0.15f..0.9f && cr > 0.01f && cb < 0.02f) { nb += cb; nr += cr; nn++ }
                }
                tx += 4f
            }
            ty += 4f
        }
        if (nn < 60) {
            // neck hidden (collar, scarf): the ears are skin too, beside the eyes
            nb = 0.0; nr = 0.0; nn = 0
            for (side in 0..1) {
                val x0 = if (side == 0) 112f else 368f
                var ty2 = 250f
                while (ty2 < 340f) {
                    var tx2 = x0
                    while (tx2 < x0 + 32f) {
                        pt[0] = tx2; pt[1] = ty2; inv.mapPoints(pt)
                        val x = pt[0].toInt(); val y = pt[1].toInt()
                        if (x in 0 until src.width && y in 0 until src.height) {
                            val c = src.getPixel(x, y)
                            val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
                            val yy = 0.299f * r + 0.587f * g + 0.114f * b
                            val cb = -0.168736f * r - 0.331264f * g + 0.5f * b
                            val cr = 0.5f * r - 0.418688f * g - 0.081312f * b
                            if (yy in 0.15f..0.9f && cr > 0.01f && cb < 0.02f) { nb += cb; nr += cr; nn++ }
                        }
                        tx2 += 4f
                    }
                    ty2 += 4f
                }
            }
            if (nn < 30) return 0f to 0f
        }
        var fb = 0.0; var fr = 0.0; var fn = 0
        for (y in 280 until 380 step 2) for (x in 180 until 332 step 2) {
            val c = facePx[y * S + x]
            if (c and 0xFFFFFF == 0) continue
            val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
            fb += -0.168736f * r - 0.331264f * g + 0.5f * b
            fr += 0.5f * r - 0.418688f * g - 0.081312f * b
            fn++
        }
        if (fn < 100) return 0f to 0f
        val db = ((nb / nn - fb / fn) * 0.5).toFloat().coerceIn(-0.04f, 0.04f)
        val dr = ((nr / nn - fr / fn) * 0.5).toFloat().coerceIn(-0.04f, 0.04f)
        return db to dr
    }

    /** Soft blur of a 512×512 plane (three box passes ≈ gaussian, radius 10): only broad shapes. */
    private fun blur(src: FloatArray): FloatArray {
        var a = src.copyOf(); var b = FloatArray(N)
        val r = 10
        repeat(3) {
            for (y in 0 until S) {               // horizontal
                var acc = 0f
                for (x in -r..r) acc += a[y * S + x.coerceIn(0, S - 1)]
                for (x in 0 until S) {
                    b[y * S + x] = acc / (2 * r + 1)
                    acc += a[y * S + (x + r + 1).coerceAtMost(S - 1)] - a[y * S + (x - r).coerceAtLeast(0)]
                }
            }
            for (x in 0 until S) {               // vertical
                var acc = 0f
                for (y in -r..r) acc += b[y.coerceIn(0, S - 1) * S + x]
                for (y in 0 until S) {
                    a[y * S + x] = acc / (2 * r + 1)
                    acc += b[(y + r + 1).coerceAtMost(S - 1) * S + x] - b[(y - r).coerceAtLeast(0) * S + x]
                }
            }
        }
        return a
    }

    private fun to8(v: Float) = ((v + 1f) * 127.5f + 0.5f).toInt().coerceIn(0, 255)

    override fun close() {
        interpreter?.close(); interpreter = null
        cf?.close(); cf = null
        gpu?.close()
        gpu = null
    }

    companion object {
        private const val S = 512
        private const val N = S * S
        private const val FEATHER = 48f
        // face oval in the 512 FFHQ template (eyes at y≈240, mouth at y≈371)
        private const val OVAL_CX = 256f
        private const val OVAL_CY = 300f
        private const val OVAL_RX = 168f
        private const val OVAL_RY = 205f
        private const val OVAL_FEATHER = 0.35f
        /** How much of the original's broad brightness the restored face keeps (0 = none, 1 = all). */
        private const val LOW_KEEP = 0.35f
        /** Same, inside the eyes: the model's invented iris color and brightness mostly give way. */
        private const val EYE_KEEP = 0.95f
        private const val URL_MODEL = "https://huggingface.co/litert-community/GFPGAN-v1.4-LiteRT/resolve/main/gfpgan_fp16.tflite"
        const val APPROX_MB = 431

        /** FFHQ 512 five-point template used by GFPGAN (facexlib). */
        private val TEMPLATE = arrayOf(
            PointF(192.98138f, 239.94708f), PointF(318.90277f, 240.1936f), PointF(256.63416f, 314.01935f),
            PointF(201.26117f, 371.41043f), PointF(313.08905f, 371.15118f)
        )

        fun modelFile(ctx: Context) = File(ctx.filesDir, "gfpgan_fp16.tflite")
        fun isReady(ctx: Context) = modelFile(ctx).let { it.exists() && it.length() > 100_000_000 }

        /** Least-squares similarity (rotation + uniform scale + translation) mapping [src] onto [dst]. */
        fun similarity(src: Array<PointF>, dst: Array<PointF>): Matrix {
            val n = src.size
            var sx = 0f; var sy = 0f; var dx = 0f; var dy = 0f
            for (i in 0 until n) { sx += src[i].x; sy += src[i].y; dx += dst[i].x; dy += dst[i].y }
            sx /= n; sy /= n; dx /= n; dy /= n
            var a = 0f; var b = 0f; var den = 0f
            for (i in 0 until n) {
                val px = src[i].x - sx; val py = src[i].y - sy
                val qx = dst[i].x - dx; val qy = dst[i].y - dy
                a += px * qx + py * qy
                b += px * qy - py * qx
                den += px * px + py * py
            }
            if (den < 1e-6f) den = 1e-6f
            a /= den; b /= den
            // [a -b tx; b a ty]
            val tx = dx - (a * sx - b * sy)
            val ty = dy - (b * sx + a * sy)
            return Matrix().apply { setValues(floatArrayOf(a, -b, tx, b, a, ty, 0f, 0f, 1f)) }
        }

        /** Downloads the GFPGAN model once, resuming a partial download. */
        fun download(ctx: Context, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) {
            val target = modelFile(ctx)
            val part = File(target.path + ".part")
            var attempt = 0
            while (true) {
                try {
                    fetch(part, cancelled, onProgress)
                    break
                } catch (e: IOException) {
                    attempt++
                    if (attempt >= 5) throw e
                    Thread.sleep(2000L * attempt)
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("No se pudo guardar el modelo")
        }

        private fun fetch(part: File, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) {
            val have = if (part.exists()) part.length() else 0L
            val conn = (URL(URL_MODEL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                if (code == 416) return
                val partial = code == HttpURLConnection.HTTP_PARTIAL
                if (code != HttpURLConnection.HTTP_OK && !partial) throw IOException("HTTP $code")
                val total = (if (partial) have else 0L) + conn.contentLengthLong.coerceAtLeast(0)
                var got = if (partial) have else 0L
                conn.inputStream.use { input ->
                    FileOutputStream(part, partial).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var last = 0L
                        while (true) {
                            if (cancelled()) throw InterruptedException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (got - last > 2_000_000) { onProgress(got, total); last = got }
                        }
                    }
                }
                onProgress(got, total)
            } finally {
                conn.disconnect()
            }
        }
    }
}
