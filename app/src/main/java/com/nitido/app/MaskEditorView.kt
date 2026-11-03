package com.nitido.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Paint a mask over a photo. One finger paints (or erases), two fingers zoom and move.
 * The mask is a bitmap the size of the photo: opaque = "redraw this", transparent = "keep".
 */
class MaskEditorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var photo: Bitmap? = null
        private set
    var mask: Bitmap? = null
        private set

    var erasing = false
    /** Brush diameter in screen pixels. */
    var brushPx = 60f
    var onChange: (() -> Unit)? = null
    /** Tap mode: a quick touch calls [onTap] with the point in photo pixels instead of painting. */
    var tapMode = false
    var onTap: ((Float, Float) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var multi = false

    private val undo = ArrayDeque<Bitmap>()
    private val view = Matrix()      // photo -> screen
    private val inverse = Matrix()
    private var baseScale = 1f
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f

    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 130 }
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.FILL }
    private val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }

    private var painting = false
    private var lastX = 0f
    private var lastY = 0f
    private var ringX = -1f
    private var ringY = -1f
    private var pinchDist = 0f
    private var pinchMidX = 0f
    private var pinchMidY = 0f

    fun setPhoto(bmp: Bitmap, existingMask: Bitmap? = null) {
        photo = bmp
        mask = (existingMask?.copy(Bitmap.Config.ARGB_8888, true))
            ?: Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        undo.clear()
        zoom = 1f; tx = 0f; ty = 0f
        layoutMatrix(); invalidate()
    }

    fun hasMask(): Boolean {
        val m = mask ?: return false
        val small = Bitmap.createScaledBitmap(m, max(1, m.width / 8), max(1, m.height / 8), false)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        return px.any { (it ushr 24) > 20 }
    }

    fun undoLast() {
        val prev = undo.removeLastOrNull() ?: return
        mask = prev
        invalidate(); onChange?.invoke()
    }

    fun clear() {
        val m = mask ?: return
        pushUndo()
        m.eraseColor(Color.TRANSPARENT)
        invalidate(); onChange?.invoke()
    }

    fun invert() {
        val m = mask ?: return
        pushUndo()
        val px = IntArray(m.width * m.height)
        m.getPixels(px, 0, m.width, 0, 0, m.width, m.height)
        for (i in px.indices) {
            val a = 255 - (px[i] ushr 24)
            px[i] = Color.argb(a, 255, 0, 0)
        }
        m.setPixels(px, 0, m.width, 0, 0, m.width, m.height)
        invalidate(); onChange?.invoke()
    }

    /** Use an automatic selection ([conf] 0..1 per pixel, [w]×[h]) as the mask, added to what's painted. */
    fun addSelection(conf: FloatArray, w: Int, h: Int, threshold: Float = 0.5f) {
        val m = mask ?: return
        pushUndo()
        val sel = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (i in px.indices) px[i] = if (conf[i] >= threshold) Color.RED else Color.TRANSPARENT
        sel.setPixels(px, 0, w, 0, 0, w, h)
        Canvas(m).drawBitmap(sel, null, android.graphics.Rect(0, 0, m.width, m.height), Paint(Paint.FILTER_BITMAP_FLAG))
        invalidate(); onChange?.invoke()
    }

    private fun pushUndo() {
        val m = mask ?: return
        undo.addLast(m.copy(Bitmap.Config.ARGB_8888, true))
        while (undo.size > 12) undo.removeFirst()
    }

    private fun layoutMatrix() {
        val p = photo ?: return
        if (width == 0 || height == 0) return
        baseScale = min(width / p.width.toFloat(), height / p.height.toFloat())
        val s = baseScale * zoom
        val dx = (width - p.width * s) / 2f + tx
        val dy = (height - p.height * s) / 2f + ty
        view.reset(); view.postScale(s, s); view.postTranslate(dx, dy)
        view.invert(inverse)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutMatrix() }

    override fun onDraw(c: Canvas) {
        val p = photo ?: return
        layoutMatrix()
        c.drawBitmap(p, view, photoPaint)
        mask?.let { c.drawBitmap(it, view, maskPaint) }
        if (ringX >= 0) c.drawCircle(ringX, ringY, brushPx / 2f, ring)
    }

    private fun toPhoto(x: Float, y: Float): FloatArray = floatArrayOf(x, y).also { inverse.mapPoints(it) }

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float) {
        val m = mask ?: return
        val canvas = Canvas(m)
        val a = toPhoto(x0, y0); val b = toPhoto(x1, y1)
        val r = brushPx / 2f / (baseScale * zoom)
        val paint = if (erasing) eraser else brush
        val dist = hypot(b[0] - a[0], b[1] - a[1])
        val steps = max(1, (dist / (r * 0.35f)).toInt())
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            canvas.drawCircle(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, r, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (photo == null) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; multi = false
                if (tapMode) return true
                painting = true
                pushUndo()
                lastX = e.x; lastY = e.y
                ringX = e.x; ringY = e.y
                stroke(e.x, e.y, e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                if (painting) { undoLast(); painting = false }   // a second finger means zoom, not paint
                ringX = -1f
                pinchDist = dist(e); pinchMidX = midX(e); pinchMidY = midY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val d = dist(e); val mx = midX(e); val my = midY(e)
                    if (pinchDist > 0f) {
                        val old = zoom
                        zoom = (zoom * d / pinchDist).coerceIn(1f, 12f)
                        val k = zoom / old
                        val cx = width / 2f + tx; val cy = height / 2f + ty
                        tx += (mx - cx) * (1 - k) + (mx - pinchMidX)
                        ty += (my - cy) * (1 - k) + (my - pinchMidY)
                    }
                    pinchDist = d; pinchMidX = mx; pinchMidY = my
                    invalidate()
                } else if (painting) {
                    stroke(lastX, lastY, e.x, e.y)
                    lastX = e.x; lastY = e.y
                    ringX = e.x; ringY = e.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tapMode && e.actionMasked == MotionEvent.ACTION_UP && !multi &&
                    hypot(e.x - downX, e.y - downY) < 24f * resources.displayMetrics.density) {
                    val p = photo
                    val pt = toPhoto(e.x, e.y)
                    if (p != null && pt[0] in 0f..p.width.toFloat() && pt[1] in 0f..p.height.toFloat()) onTap?.invoke(pt[0], pt[1])
                }
                if (painting) onChange?.invoke()
                painting = false
                ringX = -1f
                invalidate()
            }
        }
        return true
    }

    private fun dist(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun midX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2f
    private fun midY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2f

    fun resetZoom() { zoom = 1f; tx = 0f; ty = 0f; invalidate() }
}
