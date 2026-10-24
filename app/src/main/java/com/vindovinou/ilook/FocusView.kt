package com.vindovinou.ilook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** iOS-style yellow focus square with the exposure "sun" next to it. */
class FocusView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xFFFFD60A.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD60A.toInt() }

    private var cx = -1f
    private var cy = -1f
    /** -1..1, sun position relative to the square. */
    private var ev = 0f
    private val hide = Runnable { cx = -1f; invalidate() }

    fun show(x: Float, y: Float) {
        cx = x; cy = y; ev = 0f
        alpha = 1f
        scaleX = 1f; scaleY = 1f
        invalidate()
        removeCallbacks(hide)
        postDelayed(hide, 2500)
    }

    fun setEv(fraction: Float) {
        if (cx < 0) return
        ev = fraction.coerceIn(-1f, 1f)
        invalidate()
        removeCallbacks(hide)
        postDelayed(hide, 2500)
    }

    val isShowing get() = cx >= 0

    override fun onDraw(canvas: Canvas) {
        if (cx < 0) return
        val half = 38f * density
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint)
        val t = 5f * density
        canvas.drawLine(cx, cy - half, cx, cy - half + t, paint)
        canvas.drawLine(cx, cy + half, cx, cy + half - t, paint)
        canvas.drawLine(cx - half, cy, cx - half + t, cy, paint)
        canvas.drawLine(cx + half, cy, cx + half - t, cy, paint)
        // Exposure sun on the right side.
        val sx = cx + half + 16f * density
        val sy = cy - ev * half
        canvas.drawLine(sx, cy - half, sx, sy - 8 * density, paint)
        canvas.drawLine(sx, sy + 8 * density, sx, cy + half, paint)
        canvas.drawCircle(sx, sy, 4.5f * density, fill)
    }
}
