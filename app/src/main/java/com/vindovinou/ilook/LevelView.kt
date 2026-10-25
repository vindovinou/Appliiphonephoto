package com.vindovinou.ilook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2

/**
 * iOS "Niveau": a horizon line in the middle of the viewfinder that appears when the phone is
 * nearly level and turns yellow, as one straight line, once it is level.
 */
class LevelView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs), SensorEventListener {

    var active = false
        set(value) {
            field = value
            if (value) start() else stop()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 1.5f * density
    }
    private val yellow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD60A.toInt()
        strokeWidth = 1.5f * density
    }

    /** Tilt from the nearest level orientation, degrees; NaN when unknown. */
    private var tilt = Float.NaN
    private var listening = false

    private fun start() {
        if (listening) return
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            listening = true
        }
    }

    private fun stop() {
        if (!listening) return
        sensors.unregisterListener(this)
        listening = false
        tilt = Float.NaN
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // Pointing at the floor or the sky: iOS shows a different (crosshair) level; skip it.
        if (abs(z) > 8f) {
            tilt = Float.NaN
            invalidate()
            return
        }
        // Roll of the screen relative to gravity, folded to the nearest 90°.
        var deg = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        while (deg > 45f) deg -= 90f
        while (deg < -45f) deg += 90f
        tilt = deg
        invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        val t = tilt
        if (!active || t.isNaN() || abs(t) > 10f) return
        val cx = width / 2f
        val cy = height / 2f
        val half = 60f * density
        val gap = 6f * density
        if (abs(t) < 1f) {
            canvas.drawLine(cx - half - 24 * density, cy, cx + half + 24 * density, cy, yellow)
            return
        }
        // Fixed outer stubs, plus the centre line rotated by the tilt.
        canvas.drawLine(cx - half - 24 * density, cy, cx - half - gap, cy, white)
        canvas.drawLine(cx + half + gap, cy, cx + half + 24 * density, cy, white)
        canvas.save()
        canvas.rotate(-t, cx, cy)
        canvas.drawLine(cx - half, cy, cx + half, cy, white)
        canvas.restore()
    }
}
