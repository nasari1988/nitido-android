package com.nitido.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The old-photo restoration pack (published by "Convertir modelo" under the name "restauracion"):
 * SCUNet (noise and grain), CodeFormer (faces), LaMa (scratches and stains), DDColor (colorize).
 */
object RestorePack {
    const val ID = "restauracion"
    const val DENOISE = "rest_denoise.mnn"
    const val DEBLUR = "rest_deblur.mnn"
    const val FACES = "rest_faces.mnn"
    const val LAMA = "rest_lama.mnn"
    const val COLOR = "rest_color.mnn"

    fun isReady(ctx: Context) = SdModel.isReady(ctx, ID)

    /** The model file, if the pack is installed and that model made it into the pack. */
    fun file(ctx: Context, name: String): File? =
        File(SdModel.dir(ctx, ID), name).takeIf { isReady(ctx) && it.exists() && it.length() > 0 }

    fun download(ctx: Context, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) =
        SdModel.download(ctx, ID, cancelled, onProgress)
}

/** Classic (non-AI) fixes and the glue around the AI models. */
object RestoreTools {

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun mutable(b: Bitmap, px: IntArray): Bitmap =
        Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, b.width, 0, 0, b.width, b.height) }

    /** True when the photo has (almost) no color: black and white or sepia-free gray. */
    fun isGrayscale(b: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(b, 128, max(1, 128 * b.height / b.width), true)
        val px = pixels(s)
        var spread = 0.0
        for (p in px) {
            val r = Color.red(p); val g = Color.green(p); val bl = Color.blue(p)
            spread += max(r, max(g, bl)) - min(r, min(g, bl))
        }
        return spread / px.size < 12
    }

    /** True for sepia / single-tone prints: little color, almost all of it the same warm tint. */
    fun isSepia(b: Bitmap): Boolean {
        val sm = Bitmap.createScaledBitmap(b, 128, max(1, 128 * b.height / b.width), true)
        val px = pixels(sm)
        var spread = 0.0; var warm = 0; var ordered = 0
        for (p in px) {
            val r = Color.red(p); val g = Color.green(p); val bl = Color.blue(p)
            spread += max(r, max(g, bl)) - min(r, min(g, bl))
            if (r - bl > 6) warm++
            if (r >= g - 3 && g >= bl - 3) ordered++
        }
        return spread / px.size < 45 && warm > px.size * 0.7 && ordered > px.size * 0.8
    }

    /**
     * Faded photos: stretches each color channel on its own between its darkest and brightest
     * values (which also removes the pink/yellow cast of old prints), then adds a little color back.
     */
    fun fixColor(src: Bitmap, saturate: Boolean, gentle: Boolean = false): Bitmap {
        val px = pixels(src)
        val hist = Array(3) { IntArray(256) }
        val step = max(1, px.size / 300_000)
        var n = 0
        for (i in px.indices step step) {
            val p = px[i]
            hist[0][Color.red(p)]++; hist[1][Color.green(p)]++; hist[2][Color.blue(p)]++
            n++
        }
        fun pct(h: IntArray, q: Float): Int {
            val target = (n * q).toInt(); var acc = 0
            for (v in 0..255) { acc += h[v]; if (acc > target) return v }
            return 255
        }
        val lut = Array(3) { c ->
            // Black-and-white, sepia, or about to be colorized: a softer stretch that keeps the
            // whites (old prints are flat, and a full stretch burns the light areas).
            var lo = pct(hist[c], if (gentle) 0.005f else 0.004f).toFloat()
            var hi = pct(hist[c], if (gentle) 0.999f else 0.996f).toFloat()
            val minRange = if (gentle) 140f else 40f
            if (hi - lo < minRange) { val mid = (hi + lo) / 2; lo = mid - minRange / 2; hi = mid + minRange / 2 }   // don't blow up flat images
            IntArray(256) { v -> ((v - lo) / (hi - lo) * 255f).roundToInt().coerceIn(0, 255) }
        }
        for (i in px.indices) {
            val p = px[i]
            px[i] = Color.rgb(lut[0][Color.red(p)], lut[1][Color.green(p)], lut[2][Color.blue(p)])
        }
        neutralize(px, src.width, src.height)
        val sat = if (saturate) 1.12f else 1f
        for (i in px.indices) {
            val p = px[i]
            var r = Color.red(p).toFloat(); var g = Color.green(p).toFloat(); var b = Color.blue(p).toFloat()
            if (sat != 1f) {
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                r = y + (r - y) * sat; g = y + (g - y) * sat; b = y + (b - y) * sat
            }
            px[i] = Color.rgb(r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255))
        }
        return mutable(src, px)
    }

    /**
     * Removes a leftover color cast (the green / pink of faded prints): the nearly gray areas of
     * the photo (walls, floor, white clothes) are assumed to be gray, and the whole photo is
     * shifted by most of the color they show. Strongly colored areas don't take part.
     */
    private fun neutralize(px: IntArray, w: Int, h: Int) {
        // Faded prints often have a cast that changes across the photo (green on one side, pink
        // on the other), so the cast is measured in a 4×4 grid of zones and the correction is
        // blended smoothly between zone centers. Zones with few gray pixels lean on the average.
        val g = 4
        val lab = FloatArray(3)
        val sa = DoubleArray(g * g); val sb = DoubleArray(g * g); val cnt = IntArray(g * g)
        var ta = 0.0; var tb = 0.0; var tn = 0
        val step = max(1, kotlin.math.sqrt(px.size / 200_000.0).toInt())
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                toLab(px[y * w + x], lab)
                val chroma = Math.hypot(lab[1].toDouble(), lab[2].toDouble())
                if (lab[0] in 15f..92f && chroma < 22.0) {
                    val c = min(g - 1, y * g / h) * g + min(g - 1, x * g / w)
                    sa[c] = sa[c] + lab[1]; sb[c] = sb[c] + lab[2]; cnt[c]++
                    ta += lab[1]; tb += lab[2]; tn++
                }
                x += step
            }
            y += step
        }
        if (tn < 500) return
        val ma = ta / tn; val mb = tb / tn
        val perCell = tn / (g * g).toDouble()
        val oa = FloatArray(g * g); val ob = FloatArray(g * g)
        for (c in 0 until g * g) {
            val trust = if (cnt[c] == 0) 0.0 else minOf(1.0, cnt[c] / (perCell * 0.5))
            val a = if (cnt[c] == 0) ma else trust * sa[c] / cnt[c] + (1 - trust) * ma
            val b = if (cnt[c] == 0) mb else trust * sb[c] / cnt[c] + (1 - trust) * mb
            oa[c] = (-0.8 * a).toFloat(); ob[c] = (-0.8 * b).toFloat()
        }
        if (oa.all { kotlin.math.abs(it) < 0.75f } && ob.all { kotlin.math.abs(it) < 0.75f }) return
        for (yy in 0 until h) {
            val fy = ((yy + 0.5f) * g / h - 0.5f).coerceIn(0f, g - 1f)
            val y0 = fy.toInt(); val y1 = min(y0 + 1, g - 1); val ty = fy - y0
            for (xx in 0 until w) {
                val fx = ((xx + 0.5f) * g / w - 0.5f).coerceIn(0f, g - 1f)
                val x0 = fx.toInt(); val x1 = min(x0 + 1, g - 1); val tx = fx - x0
                fun bil(o: FloatArray) = (o[y0 * g + x0] * (1 - tx) + o[y0 * g + x1] * tx) * (1 - ty) +
                    (o[y1 * g + x0] * (1 - tx) + o[y1 * g + x1] * tx) * ty
                val i = yy * w + xx
                toLab(px[i], lab)
                px[i] = fromLab(lab[0], lab[1] + bil(oa), lab[2] + bil(ob))
            }
        }
    }

    /**
     * The part of a photographed or scanned print without the background strips around it
     * (scanner lid, table, cloth): near each edge (up to 6%) it looks for a sharp jump in the
     * average color of rows / columns. Returns the whole picture when there's no clear border.
     */
    fun borderCrop(src: Bitmap): android.graphics.Rect {
        val k = minOf(1f, 480f / maxOf(src.width, src.height))
        val sm = Bitmap.createScaledBitmap(src, maxOf(8, (src.width * k).toInt()), maxOf(8, (src.height * k).toInt()), true)
        val w = sm.width; val h = sm.height
        val px = pixels(sm)
        fun profile(rows: Boolean): Array<FloatArray> {
            val n = if (rows) h else w; val m = if (rows) w else h
            return Array(n) { i ->
                var r = 0f; var g = 0f; var b = 0f; var c = 0
                for (j in (m / 10) until (m - m / 10)) {
                    val p = if (rows) px[i * w + j] else px[j * w + i]
                    r += Color.red(p); g += Color.green(p); b += Color.blue(p); c++
                }
                floatArrayOf(r / c, g / c, b / c)
            }
        }
        /** Index of the first line inside the photo, counting from the edge, or 0. */
        fun cut(prof: Array<FloatArray>, fromEnd: Boolean): Int {
            val n = prof.size
            val max = maxOf(2, (n * 0.06f).toInt())
            fun at(i: Int) = prof[if (fromEnd) n - 1 - i else i]
            fun band(a: Int, b: Int): FloatArray {
                val o = FloatArray(3); var c = 0
                for (i in a until b) { val v = at(i.coerceIn(0, n - 1)); o[0] += v[0]; o[1] += v[1]; o[2] += v[2]; c++ }
                return floatArrayOf(o[0] / c, o[1] / c, o[2] / c)
            }
            var best = 0f; var where = 0
            val win = maxOf(2, n / 100)
            for (j in win until max) {
                val a = band(j - win, j); val b = band(j, j + win)
                val d = kotlin.math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
                if (d > best) { best = d; where = j }
            }
            return if (best > 35f && where >= maxOf(2, n / 200)) (where + maxOf(1, n / 200)) else 0
        }
        val rows = profile(true); val cols = profile(false)
        val top = cut(rows, false); val bottom = cut(rows, true)
        val left = cut(cols, false); val right = cut(cols, true)
        val inv = 1f / k
        val r = android.graphics.Rect((left * inv).toInt(), (top * inv).toInt(),
            src.width - (right * inv).toInt(), src.height - (bottom * inv).toInt())
        return if (r.width() > src.width / 2 && r.height() > src.height / 2) r else android.graphics.Rect(0, 0, src.width, src.height)
    }

    /** [a] + ([b] - [a]) × [k], pixel by pixel ([b] same size as [a]). */
    fun mix(a: Bitmap, b: Bitmap, k: Float): Bitmap {
        val pa = pixels(a); val pb = pixels(if (b.width == a.width && b.height == a.height) b else Bitmap.createScaledBitmap(b, a.width, a.height, true))
        for (i in pa.indices) {
            val x = pa[i]; val y = pb[i]
            fun m(sh: Int) = (((x shr sh) and 0xFF) + ((((y shr sh) and 0xFF) - ((x shr sh) and 0xFF)) * k)).roundToInt().coerceIn(0, 255)
            pa[i] = Color.rgb(m(16), m(8), m(0))
        }
        return mutable(a, pa)
    }

    /**
     * Colorizes with DDColor. The model sees the photo in gray at its own size and returns the Lab
     * color channels (a, b); those are enlarged and combined with the photo's own full-size
     * lightness (L), as the official DDColor pipeline does.
     */
    fun colorize(ctx: Context, model: File, src: Bitmap, gpu: Boolean): Bitmap {
        // CPU on purpose: one 512×512 pass takes a few seconds, and it is exact there
        val tile = MnnTile(ctx, model.absolutePath, false, precise = true)
        tile.use { net ->
            val s = net.inShape[2]
            val n = s * s
            val sp = pixels(Bitmap.createScaledBitmap(src, s, s, true))
            val input = FloatArray(3 * n)
            for (i in 0 until n) {
                // a gray with the same lightness as the pixel (Lab L with a = b = 0, back in sRGB)
                val g = grayOf(sp[i])
                input[i] = g; input[n + i] = g; input[2 * n + i] = g
            }
            val ab = FloatArray(2 * n)
            if (!net.run(input, ab)) throw IllegalStateException("DDColor failed")
            val w = src.width; val h = src.height
            val px = pixels(src)
            val lab = FloatArray(3)
            val L = FloatArray(w * h); val A = FloatArray(w * h); val B = FloatArray(w * h)
            for (y in 0 until h) {
                val fy = ((y + 0.5f) * s / h - 0.5f).coerceIn(0f, s - 1f)
                val y0 = fy.toInt(); val y1 = min(y0 + 1, s - 1); val ty = fy - y0
                for (x in 0 until w) {
                    val fx = ((x + 0.5f) * s / w - 0.5f).coerceIn(0f, s - 1f)
                    val x0 = fx.toInt(); val x1 = min(x0 + 1, s - 1); val tx = fx - x0
                    fun bil(off: Int): Float {
                        val a = ab[off + y0 * s + x0] * (1 - tx) + ab[off + y0 * s + x1] * tx
                        val b = ab[off + y1 * s + x0] * (1 - tx) + ab[off + y1 * s + x1] * tx
                        return a * (1 - ty) + b * ty
                    }
                    val i = y * w + x
                    toLab(px[i], lab)
                    L[i] = lab[0] / 100f
                    // DDColor's colors are strong: keep about 70% of them
                    A[i] = bil(0) * COLOR_SAT; B[i] = bil(n) * COLOR_SAT
                }
            }
            // The colors come at 512 px: fit them to the photo's own edges (guided filter on the
            // lightness) so they don't spill over edges as purple / rainbow halos.
            val r = maxOf(4, maxOf(w, h) / 220)
            val A2 = guided(L, A, w, h, r, 1e-3f); val B2 = guided(L, B, w, h, r, 1e-3f)
            for (i in px.indices) px[i] = fromLab(L[i] * 100f, A2[i], B2[i])
            return mutable(src, px)
        }
    }

    private const val COLOR_SAT = 0.72f

    /** Mean over a (2r+1)² square, edges clamped (two running-sum passes). */
    private fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        val n = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            val o = y * w
            var acc = 0f
            for (x in -r..r) acc += src[o + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[o + x] = acc / n
                acc += src[o + (x + r + 1).coerceAtMost(w - 1)] - src[o + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (y in -r..r) acc += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc / n
                acc += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    /** Guided filter (He et al.): smooths [p] but keeps the edges of guide [g]. */
    private fun guided(g: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val mI = box(g, w, h, r); val mP = box(p, w, h, r)
        val gg = FloatArray(n) { g[it] * g[it] }; val gp = FloatArray(n) { g[it] * p[it] }
        val cI = box(gg, w, h, r); val cIP = box(gp, w, h, r)
        val a = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            val v = cI[i] - mI[i] * mI[i]
            a[i] = (cIP[i] - mI[i] * mP[i]) / (v + eps)
            b[i] = mP[i] - a[i] * mI[i]
        }
        val mA = box(a, w, h, r); val mB = box(b, w, h, r)
        return FloatArray(n) { mA[it] * g[it] + mB[it] }
    }

    // ---- sRGB <-> CIE Lab (D65), as OpenCV does for float images ----
    private fun lin(c: Float) = if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    private fun gam(c: Float) = if (c <= 0.0031308f) 12.92f * c else (1.055f * Math.pow(c.toDouble(), 1 / 2.4).toFloat() - 0.055f)
    private fun f(t: Float) = if (t > 0.008856f) Math.cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f
    private fun fi(t: Float) = if (t > 0.206893f) t * t * t else (t - 16f / 116f) / 7.787f

    private val linLut by lazy { FloatArray(256) { lin(it / 255f) } }

    private fun toLab(p: Int, out: FloatArray) {
        val r = linLut[Color.red(p)]; val g = linLut[Color.green(p)]; val b = linLut[Color.blue(p)]
        val x = (0.412453f * r + 0.357580f * g + 0.180423f * b) / 0.950456f
        val y = 0.212671f * r + 0.715160f * g + 0.072169f * b
        val z = (0.019334f * r + 0.119193f * g + 0.950227f * b) / 1.088754f
        val fy = f(y)
        out[0] = 116f * fy - 16f
        out[1] = 500f * (f(x) - fy)
        out[2] = 200f * (fy - f(z))
    }

    private fun fromLab(l: Float, a: Float, b: Float): Int {
        val fy = (l + 16f) / 116f
        val x = fi(fy + a / 500f) * 0.950456f
        val y = fi(fy)
        val z = fi(fy - b / 200f) * 1.088754f
        val r = 3.240479f * x - 1.537150f * y - 0.498535f * z
        val g = -0.969256f * x + 1.875992f * y + 0.041556f * z
        val bl = 0.055648f * x - 0.204043f * y + 1.057311f * z
        fun to8(v: Float) = (gam(v.coerceIn(0f, 1f)) * 255f).roundToInt().coerceIn(0, 255)
        return Color.rgb(to8(r), to8(g), to8(bl))
    }

    /** sRGB gray (0..1) with the same Lab lightness as [p]. */
    private fun grayOf(p: Int): Float {
        val y = 0.212671f * lin(Color.red(p) / 255f) + 0.715160f * lin(Color.green(p) / 255f) + 0.072169f * lin(Color.blue(p) / 255f)
        return gam(y)
    }

    /**
     * Removes painted scratches, dust and stains with LaMa, working in 512×512 windows over the
     * parts of the photo that have paint, and changing only the painted pixels (slightly widened).
     */
    fun removeMarks(ctx: Context, model: File, src: Bitmap, mask: Bitmap, gpu: Boolean,
                    onProgress: (Float) -> Unit, cancelled: () -> Boolean): Bitmap? {
        val w = src.width; val h = src.height
        val m = if (mask.width == w && mask.height == h) mask else Bitmap.createScaledBitmap(mask, w, h, false)
        val ma = pixels(m)
        // widen the painted area by 2 px so the edges of each scratch are replaced too
        val hard = BooleanArray(w * h) { (ma[it] ushr 24) > 30 }
        val sel = BooleanArray(w * h)
        val r = 2
        for (y in 0 until h) for (x in 0 until w) if (hard[y * w + x]) {
            for (dy in -r..r) for (dx in -r..r) {
                val yy = y + dy; val xx = x + dx
                if (yy in 0 until h && xx in 0 until w) sel[yy * w + xx] = true
            }
        }
        val px = pixels(src)
        val t = 512; val stride = 448
        val xs = starts(w, t, stride); val ys = starts(h, t, stride)
        val todo = mutableListOf<Pair<Int, Int>>()
        for (y0 in ys) for (x0 in xs) {
            var any = false
            loop@ for (y in max(0, y0) until min(h, y0 + t)) for (x in max(0, x0) until min(w, x0 + t)) if (sel[y * w + x]) { any = true; break@loop }
            if (any) todo += x0 to y0
        }
        if (todo.isEmpty()) return src
        // CPU on purpose: LaMa's Fourier layers (written as matrix products) come out as a
        // colored grid on the phone's GPU, while the CPU gives the right result
        val tile = MnnTile(ctx, model.absolutePath, false, precise = true)
        val n = t * t
        val input = FloatArray(4 * n)
        val out = FloatArray(3 * n)
        tile.use { lama ->
            for ((k, pos) in todo.withIndex()) {
                if (cancelled()) return null
                val (x0, y0) = pos
                for (y in 0 until t) {
                    val sy = (y0 + y).coerceIn(0, h - 1)
                    for (x in 0 until t) {
                        val sx = (x0 + x).coerceIn(0, w - 1)
                        val p = px[sy * w + sx]; val i = y * t + x
                        input[i] = Color.red(p) / 255f; input[n + i] = Color.green(p) / 255f; input[2 * n + i] = Color.blue(p) / 255f
                        input[3 * n + i] = if (sel[sy * w + sx]) 1f else 0f
                    }
                }
                if (!lama.run(input, out)) throw IllegalStateException("LaMa failed")
                for (y in 0 until t) {
                    val sy = y0 + y; if (sy !in 0 until h) continue
                    for (x in 0 until t) {
                        val sx = x0 + x; if (sx !in 0 until w) continue
                        val j = sy * w + sx
                        if (!sel[j]) continue
                        val i = y * t + x
                        px[j] = Color.rgb((out[i] * 255f).roundToInt().coerceIn(0, 255), (out[n + i] * 255f).roundToInt().coerceIn(0, 255),
                            (out[2 * n + i] * 255f).roundToInt().coerceIn(0, 255))
                    }
                }
                onProgress((k + 1f) / todo.size)
            }
        }
        return mutable(src, px)
    }

    /** Window start positions covering [len] with windows of [t] (centered when the image is smaller). */
    private fun starts(len: Int, t: Int, stride: Int): List<Int> {
        if (len <= t) return listOf(-(t - len) / 2)
        val out = mutableListOf<Int>()
        var p = 0
        while (true) {
            if (p + t >= len) { out += len - t; break }
            out += p; p += stride
        }
        return out
    }
}
