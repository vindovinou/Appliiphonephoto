package com.vindovinou.ilook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * iPhone shutter: white ring with a white disc (photo), red disc (video), shrinking to a red
 * rounded square while recording.
 */
class ShutterButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class State { PHOTO, VIDEO, RECORDING }

    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = 0xFFFFFFFF.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** 0 = full disc, 1 = recording square. */
    private var morph = 0f
    private var red = 0f
    private var pressedScale = 1f
    var state = State.PHOTO
        private set

    fun setState(s: State) {
        if (s == state) return
        state = s
        animateTo(
            targetMorph = if (s == State.RECORDING) 1f else 0f,
            targetRed = if (s == State.PHOTO) 0f else 1f,
        )
    }

    private fun animateTo(targetMorph: Float, targetRed: Float) {
        val m0 = morph
        val r0 = red
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                morph = m0 + (targetMorph - m0) * t
                red = r0 + (targetRed - r0) * t
                invalidate()
            }
            start()
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        pressedScale = if (pressed && state == State.PHOTO) 0.9f else 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) / 2f - ring.strokeWidth / 2f
        canvas.drawCircle(cx, cy, outer, ring)

        val r = (0xFF * (1 - red) + 0xFF * red).toInt()
        val g = (0xFF * (1 - red) + 0x3B * red).toInt()
        val bl = (0xFF * (1 - red) + 0x30 * red).toInt()
        fill.color = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl

        val disc = (outer - 5f * density) * pressedScale
        val square = outer * 0.42f
        val half = disc + (square - disc) * morph
        val corner = half * (1f - morph) + 6f * density * morph
        rect.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(rect, corner, corner, fill)
    }
}
