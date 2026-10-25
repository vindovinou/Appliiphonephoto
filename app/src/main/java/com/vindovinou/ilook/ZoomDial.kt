package com.vindovinou.ilook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The iPhone's one-finger zoom dial: a half wheel with ticks that you slide left and right.
 * Zoom is laid out on a log scale so each lens step takes the same swing.
 */
class ZoomDial @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var onZoom: ((Float) -> Unit)? = null
    var onHidden: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var minZoom = 0.6f
    private var maxZoom = 40f
    private var zoom = 1f

    /** Labelled stops as shown on the 17 Pro Max dial (value, label). */
    private val stops = listOf(0.5f to ".5", 1f to "1", 2f to "2", 4f to "4", 8f to "8", 40f to "40")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        strokeWidth = 1.2f * density
    }
    private val major = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD60A.toInt()
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD60A.toInt() }
    private val path = Path()
    private val oval = RectF()

    /** Degrees of wheel per full zoom range. */
    private val sweep = 150f

    private var lastX = 0f
    private var lastStop = -1
    private val hide = Runnable {
        animate().alpha(0f).setDuration(200).withEndAction {
            visibility = GONE
            onHidden?.invoke()
        }.start()
    }

    fun setRange(min: Float, max: Float) {
        minZoom = min
        maxZoom = minOf(max, 40f)
        invalidate()
    }

    /** Shows the dial at [current]; it fades out a moment after the last touch. */
    fun show(current: Float) {
        zoom = current.coerceIn(minZoom, maxZoom)
        removeCallbacks(hide)
        animate().cancel()
        visibility = VISIBLE
        alpha = 1f
        invalidate()
        postDelayed(hide, 1500)
    }

    fun setZoomSilently(z: Float) {
        zoom = z.coerceIn(minZoom, maxZoom)
        invalidate()
    }

    /** Moves the dial by a horizontal finger movement of [dx] pixels. */
    fun dragBy(dx: Float) {
        val radius = wheelRadius()
        val degrees = Math.toDegrees((-dx / radius).toDouble()).toFloat()
        val p = (pos(zoom) + degrees / sweep).coerceIn(0f, 1f)
        zoom = minZoom * (maxZoom / minZoom).pow(p)
        // A light tick when crossing a lens stop, like the iPhone's haptic detents.
        val stop = stops.indexOfLast { zoom >= it.first.coerceAtLeast(minZoom) - 0.01f }
        if (stop != lastStop) {
            lastStop = stop
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        onZoom?.invoke(zoom)
        removeCallbacks(hide)
        postDelayed(hide, 1500)
        invalidate()
    }

    fun endDrag() {
        removeCallbacks(hide)
        postDelayed(hide, 1500)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                removeCallbacks(hide)
            }
            MotionEvent.ACTION_MOVE -> {
                dragBy(ev.x - lastX)
                lastX = ev.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return true
    }

    private fun pos(z: Float) = (ln(z / minZoom) / ln(maxZoom / minZoom)).coerceIn(0f, 1f)

    private fun wheelRadius() = width * 0.62f

    override fun onDraw(canvas: Canvas) {
        val r = wheelRadius()
        val cx = width / 2f
        val top = 30f * density
        val cy = top + r

        // Dark half wheel.
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, 180f, 180f, true, fill)

        val p0 = pos(zoom)
        fun angleOf(z: Float) = -90f + (pos(z) - p0) * sweep

        // Minor ticks every 1/8 of a doubling.
        var z = minZoom
        while (z <= maxZoom * 1.0001f) {
            val a = angleOf(z)
            if (a in -178f..-2f) drawTick(canvas, cx, cy, r, a, 8f * density, tick)
            z *= 2f.pow(1f / 8f)
        }
        // Lens stops: long ticks with labels.
        for ((v, text) in stops) {
            val zz = if (v < minZoom) minZoom else v
            if (zz > maxZoom) continue
            val a = angleOf(zz)
            if (a !in -178f..-2f) continue
            drawTick(canvas, cx, cy, r, a, 14f * density, major)
            val rad = Math.toRadians(a.toDouble())
            val lr = r - 30f * density
            canvas.drawText(text, cx + lr * cos(rad).toFloat(), cy + lr * sin(rad).toFloat() + 5f * density, label)
        }

        // Yellow marker and value at the top.
        path.reset()
        path.moveTo(cx - 5f * density, top - 4f * density)
        path.lineTo(cx + 5f * density, top - 4f * density)
        path.lineTo(cx, top + 4f * density)
        path.close()
        canvas.drawPath(path, marker)
        canvas.drawText(formatZoom(zoom), cx, top - 10f * density, value)
    }

    private fun drawTick(canvas: Canvas, cx: Float, cy: Float, r: Float, angle: Float, len: Float, p: Paint) {
        val rad = Math.toRadians(angle.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        canvas.drawLine(cx + (r - 2 * density) * c, cy + (r - 2 * density) * s, cx + (r - 2 * density - len) * c, cy + (r - 2 * density - len) * s, p)
    }

    companion object {
        /** iPhone-style zoom text: the S25's 0.6× ultra-wide reads as ".5". */
        fun formatZoom(z: Float): String {
            if (z < 0.95f) return if (z < 0.65f) ".5×" else String.format(Locale.FRANCE, "%.1f×", z).replace("0,", ",")
            val rounded = (z * 10).roundToInt() / 10f
            return if (abs(rounded - rounded.roundToInt()) < 0.05f) "${rounded.roundToInt()}×"
            else String.format(Locale.FRANCE, "%.1f×", rounded)
        }
    }
}
