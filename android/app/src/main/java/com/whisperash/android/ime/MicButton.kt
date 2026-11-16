package com.whisperash.android.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat
import com.whisperash.android.R

/** Big round mic key with a ring that follows the voice level, or a spinner while processing. */
class MicButton(context: Context) : View(context) {
    enum class Mode { IDLE, RECORDING, BUSY, DISABLED }

    var mode = Mode.IDLE
        set(v) { field = v; invalidate() }
    var level = 0f
        set(v) { field = field * 0.5f + v * 0.5f; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_mic)!!.mutate()
    private val arc = RectF()
    private var spin = 0f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) / 2f * 0.72f
        val d = resources.displayMetrics.density
        when (mode) {
            Mode.RECORDING -> {
                fill.color = 0x33FF4D5E
                canvas.drawCircle(cx, cy, base + (minOf(width, height) / 2f - base) * (0.35f + 0.65f * level), fill)
                fill.color = 0xFFFF4D5E.toInt()
            }
            Mode.BUSY -> fill.color = 0xFF3A3A46.toInt()
            Mode.DISABLED -> fill.color = 0xFF2A2A32.toInt()
            Mode.IDLE -> fill.color = 0xFFFF8A50.toInt()
        }
        canvas.drawCircle(cx, cy, base, fill)
        if (mode == Mode.BUSY) {
            ring.color = 0xFFFF8A50.toInt()
            ring.strokeWidth = 4 * d
            arc.set(cx - base + 6 * d, cy - base + 6 * d, cx + base - 6 * d, cy + base - 6 * d)
            canvas.drawArc(arc, spin, 100f, false, ring)
            spin = (spin + 9f) % 360f
            postInvalidateOnAnimation()
        }
        val s = (base * 0.9f).toInt()
        icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        icon.alpha = if (mode == Mode.DISABLED) 90 else 255
        icon.draw(canvas)
    }
}
