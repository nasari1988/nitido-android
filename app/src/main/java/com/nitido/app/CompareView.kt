package com.nitido.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Before/after viewer. One finger drags the divider, two fingers pinch-zoom and pan,
 * double tap toggles a close-up.
 */
class CompareView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var before: Bitmap? = null
        set(v) { field = v; resetView(); invalidate() }
    var after: Bitmap? = null
        set(v) { field = v; invalidate() }

    private var split = 0.5f
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private var lastFx = 0f
    private var lastFy = 0f
    private var multi = false

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 4f }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(6f, 0f, 2f, 0x66000000) }
    private val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val tagAfterBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0B04A.toInt() }
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 30f; isFakeBoldText = true; letterSpacing = 0.08f
    }
    private val rect = RectF()

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val old = zoom
            zoom = (zoom * d.scaleFactor).coerceIn(1f, 12f)
            val k = zoom / old
            // keep the focus point steady
            val cx = width / 2f + tx
            val cy = height / 2f + ty
            tx += (d.focusX - cx) * (1 - k)
            ty += (d.focusY - cy) * (1 - k)
            clampPan(); invalidate()
            return true
        }
    })

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.01f) resetView() else {
                val k = 4f
                zoom = k
                tx = (width / 2f - e.x) * (k - 1)
                ty = (height / 2f - e.y) * (k - 1)
                clampPan()
            }
            invalidate(); return true
        }
    })

    fun resetView() { zoom = 1f; tx = 0f; ty = 0f; split = 0.5f }

    private fun content(): Bitmap? = after ?: before

    private fun computeRect(): Boolean {
        val b = before ?: return false
        if (width == 0 || height == 0) return false
        val base = min(width / b.width.toFloat(), height / b.height.toFloat())
        val dw = b.width * base * zoom
        val dh = b.height * base * zoom
        val l = (width - dw) / 2f + tx
        val t = (height - dh) / 2f + ty
        rect.set(l, t, l + dw, t + dh)
        return true
    }

    private fun clampPan() {
        val b = before ?: return
        val base = min(width / b.width.toFloat(), height / b.height.toFloat())
        val dw = b.width * base * zoom
        val dh = b.height * base * zoom
        val mx = max(0f, (dw - width) / 2f)
        val my = max(0f, (dh - height) / 2f)
        tx = tx.coerceIn(-mx, mx)
        ty = ty.coerceIn(-my, my)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!computeRect()) return
        val b = before ?: return
        val a = after
        if (a == null) {
            canvas.drawBitmap(b, null, rect, bmpPaint)
            drawTag(canvas, "ANTES", 24f, false)
            return
        }
        canvas.drawBitmap(a, null, rect, bmpPaint)
        val sx = split * width
        canvas.save()
        canvas.clipRect(0f, 0f, sx, height.toFloat())
        canvas.drawBitmap(b, null, rect, bmpPaint)
        canvas.restore()
        canvas.drawLine(sx, 0f, sx, height.toFloat(), linePaint)
        canvas.drawCircle(sx, height / 2f, 26f, knobPaint)
        drawTag(canvas, "ANTES", 24f, false)
        drawTag(canvas, "DESPUÉS", width - 24f, true)
    }

    private fun drawTag(c: Canvas, s: String, x: Float, right: Boolean) {
        val tw = tagText.measureText(s)
        val l = if (right) x - tw - 24f else x
        val r = RectF(l, 24f, l + tw + 24f, 72f)
        c.drawRoundRect(r, 10f, 10f, if (right) tagAfterBg else tagBg)
        tagText.color = if (right) 0xFF1B1204.toInt() else Color.WHITE
        c.drawText(s, l + 12f, 58f, tagText)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (content() == null) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { multi = false; moveSplit(e.x) }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                lastFx = focusX(e); lastFy = focusY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val fx = focusX(e); val fy = focusY(e)
                    tx += fx - lastFx; ty += fy - lastFy
                    lastFx = fx; lastFy = fy
                    clampPan(); invalidate()
                } else if (!multi) moveSplit(e.x)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // re-anchor on the remaining pointers
                val skip = e.actionIndex
                var sx = 0f; var sy = 0f; var n = 0
                for (i in 0 until e.pointerCount) if (i != skip) { sx += e.getX(i); sy += e.getY(i); n++ }
                if (n > 0) { lastFx = sx / n; lastFy = sy / n }
            }
        }
        return true
    }

    private fun moveSplit(x: Float) {
        if (after == null) return
        split = (x / width).coerceIn(0f, 1f)
        invalidate()
    }

    private fun focusX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun focusY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }
}
