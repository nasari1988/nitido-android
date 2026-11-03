package com.nitido.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlin.math.roundToInt

/**
 * "Detalle IA" for restored photos: redraws the photo in overlapping pieces with one of the
 * user's Stable Diffusion models, changing it only a little ([strength]), so blurry clothes,
 * hair and walls get believable texture. The same idea as "Refinar detalle", without enlarging.
 */
object SdDetail {

    private const val PROMPT = "sharp detailed old family photograph, natural skin texture, detailed fabric, " +
        "film grain, realistic, high quality photo"
    private const val NEGATIVE = "blurry, out of focus, painting, drawing, cartoon, anime, deformed, extra fingers, " +
        "text, watermark, oversaturated, plastic skin"

    /** The Stable Diffusion model to use: the one chosen in "Crear con IA" if it's a normal model, else any. */
    fun pickModel(ctx: Context): String? {
        val chosen = ctx.getSharedPreferences("nitido", Context.MODE_PRIVATE).getString("model", null)
        val local = ModelCatalog.local(ctx)
        fun ok(id: String) = id in local && SdModel.readMode(ctx, id) == 0
        if (chosen != null && ok(chosen)) return chosen
        return local.firstOrNull { ok(it) && !SdModel.isSdxl(ctx, it) } ?: local.firstOrNull { ok(it) }
    }

    fun redraw(ctx: Context, src: Bitmap, modelId: String, strength: Float, gpu: Boolean,
               onProgress: (Int, Int) -> Unit, cancelled: () -> Boolean): Bitmap? {
        SdNative.loadError?.let { throw IllegalStateException("motor no disponible", it) }
        val sdxl = SdModel.isSdxl(ctx, modelId)
        val dynamic = SdModel.isDynamic(ctx, modelId)
        val t = if (sdxl) 768 else 512
        val overlap = 96 * t / 512
        var img = src
        if (minOf(img.width, img.height) < t) {
            val k = t.toFloat() / minOf(img.width, img.height)
            img = Bitmap.createScaledBitmap(img, (img.width * k).roundToInt(), (img.height * k).roundToInt(), true)
        }
        val w = img.width; val h = img.height
        val srcPx = IntArray(w * h); img.getPixels(srcPx, 0, w, 0, 0, w, h)
        val acc = FloatArray(w * h * 3); val wsum = FloatArray(w * h)
        val xs = positions(w, t, overlap); val ys = positions(h, t, overlap)
        val total = xs.size * ys.size
        val dir = SdModel.dir(ctx, modelId).absolutePath
        SdModel.ensureEncoder(ctx, modelId)
        val tileFile = File(ctx.cacheDir, "detail_tile.jpg")
        val seed0 = kotlin.random.Random.nextInt(1, Int.MAX_VALUE / 2)
        var sampler = SdNative.SAMPLER_DPM
        var n = 0
        try {
            for (ty in ys) for (tx in xs) {
                if (cancelled()) return null
                onProgress(n, total)
                n++
                val tile = Bitmap.createBitmap(img, tx, ty, t, t)
                var code: Int
                while (true) {
                    code = GpuMeter.track(gpu) { SdNative.generate(
                        dir, ctx.cacheDir.absolutePath, if (gpu) SdNative.BACKEND_OPENCL else SdNative.BACKEND_CPU,
                        0, dynamic, t, t, 25, seed0 + n, tileFile.absolutePath, PROMPT, NEGATIVE,
                        toInput(tile), strength, 5f, 1.5f, null, null, SdNative.PREVIEW_NONE, sampler,
                        object : SdNative.Listener {
                            override fun onProgress(pct: Int) {}
                            override fun onPreview(rgb: ByteArray, width: Int, height: Int) {}
                        }) }
                    // numbers overflowed with DPM++: the classic method for the rest
                    if (code == SdNative.BAD_UNET && sampler == SdNative.SAMPLER_DPM) { sampler = SdNative.SAMPLER_PLMS; continue }
                    break
                }
                if (code == SdNative.CANCELLED || cancelled()) return null
                if (code != 0) throw IllegalStateException("Stable Diffusion: código $code")
                val out = BitmapFactory.decodeFile(tileFile.absolutePath) ?: throw IllegalStateException("sin imagen")
                val px = IntArray(t * t); out.getPixels(px, 0, t, 0, 0, t, t)
                for (y in 0 until t) for (x in 0 until t) {
                    // feather toward tile edges, except where the tile touches the picture edge
                    val dl = if (tx == 0) 1e4f else x.toFloat()
                    val dr = if (tx + t >= w) 1e4f else (t - 1 - x).toFloat()
                    val dt = if (ty == 0) 1e4f else y.toFloat()
                    val db = if (ty + t >= h) 1e4f else (t - 1 - y).toFloat()
                    val wt = (minOf(minOf(dl, dr), minOf(dt, db)) / overlap).coerceIn(0.02f, 1f)
                    val i = (ty + y) * w + (tx + x)
                    val c = px[y * t + x]
                    acc[i * 3] += Color.red(c) * wt
                    acc[i * 3 + 1] += Color.green(c) * wt
                    acc[i * 3 + 2] += Color.blue(c) * wt
                    wsum[i] += wt
                }
            }
        } finally {
            // give the GPU memory back for the face model that comes next
            SdNative.release()
        }
        onProgress(total, total)
        val outPx = IntArray(w * h) { i ->
            val s = wsum[i]
            if (s <= 0f) srcPx[i] else Color.rgb((acc[i * 3] / s).roundToInt().coerceIn(0, 255),
                (acc[i * 3 + 1] / s).roundToInt().coerceIn(0, 255), (acc[i * 3 + 2] / s).roundToInt().coerceIn(0, 255))
        }
        // Faces stay as they were: Stable Diffusion deforms small faces, and the face model that
        // runs next restores them much better from the undistorted original.
        for (f in faces(img)) {
            val cx = f.exactCenterX(); val cy = f.exactCenterY()
            val rx = f.width() * 0.75f; val ry = f.height() * 0.85f
            val x0 = maxOf(0, (cx - rx).toInt()); val x1 = minOf(w - 1, (cx + rx).toInt())
            val y0 = maxOf(0, (cy - ry).toInt()); val y1 = minOf(h - 1, (cy + ry).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val dx = (x - cx) / rx; val dy = (y - cy) / ry
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                val t = ((1f - d) / 0.35f).coerceIn(0f, 1f)
                val k = t * t * (3f - 2f * t)
                if (k <= 0f) continue
                val i = y * w + x
                val a = srcPx[i]; val b = outPx[i]
                fun mix(sh: Int) = (((a shr sh) and 0xFF) * k + ((b shr sh) and 0xFF) * (1 - k)).roundToInt()
                outPx[i] = Color.rgb(mix(16), mix(8), mix(0))
            }
        }
        val done = Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
        return if (done.width != src.width) Bitmap.createScaledBitmap(done, src.width, src.height, true) else done
    }

    /** Face boxes (ML Kit, on the phone), in [img] pixels. */
    private fun faces(img: Bitmap): List<android.graphics.Rect> {
        val long = maxOf(img.width, img.height)
        val k = if (long > 1600) 1600f / long else 1f
        val small = if (k < 1f) Bitmap.createScaledBitmap(img, (img.width * k).toInt(), (img.height * k).toInt(), true) else img
        val det = com.google.mlkit.vision.face.FaceDetection.getClient(
            com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.05f).build())
        return try {
            com.google.android.gms.tasks.Tasks.await(det.process(com.google.mlkit.vision.common.InputImage.fromBitmap(small, 0)))
                .map { f -> val r = f.boundingBox
                    android.graphics.Rect((r.left / k).toInt(), (r.top / k).toInt(), (r.right / k).toInt(), (r.bottom / k).toInt()) }
        } catch (t: Throwable) { emptyList() } finally { det.close() }
    }

    private fun toInput(bmp: Bitmap): FloatArray {
        val n = bmp.width * bmp.height
        val px = IntArray(n); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val f = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = px[i]
            f[i] = Color.red(p) / 127.5f - 1f; f[n + i] = Color.green(p) / 127.5f - 1f; f[2 * n + i] = Color.blue(p) / 127.5f - 1f
        }
        return f
    }

    private fun positions(len: Int, t: Int, overlap: Int): List<Int> {
        if (len <= t) return listOf(0)
        val n = Math.ceil((len - t).toDouble() / (t - overlap)).toInt() + 1
        return (0 until n).map { ((len - t) * it.toFloat() / (n - 1)).roundToInt() }
    }
}
