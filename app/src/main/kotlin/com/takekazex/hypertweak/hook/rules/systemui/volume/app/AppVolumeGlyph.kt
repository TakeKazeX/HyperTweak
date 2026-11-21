package com.takekazex.hypertweak.hook.rules.systemui.volume.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The entry glyph: a three-fader mixer (one fader per app), drawn flat in one color like the
 * native silent and DND glyphs. Faders sit at different heights so it reads as "separate levels"
 * rather than a single volume bar.
 */
internal class AppVolumeGlyph(color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val rect = RectF()

    var color: Int
        get() = paint.color
        set(value) { if (paint.color != value) { paint.color = value; invalidateSelf() } }

    override fun draw(canvas: Canvas) {
        val box = bounds
        val size = minOf(box.width(), box.height()).toFloat()
        val unit = size / 24f
        val left = box.exactCenterX() - size / 2f
        val top = box.exactCenterY() - size / 2f
        // 24-unit canvas: tracks at x = 6, 12, 18; knob centers at different heights.
        val knobs = floatArrayOf(14.5f, 8f, 12f)
        knobs.forEachIndexed { index, knobY ->
            val cx = left + (6f + index * 6f) * unit
            // Thin track, with a gap around the knob so the glyph stays light at 20dp.
            val half = 1.1f * unit
            fun track(from: Float, to: Float) {
                if (to - from <= 0.2f) return
                rect.set(cx - half, top + from * unit, cx + half, top + to * unit)
                canvas.drawRoundRect(rect, half, half, paint)
            }
            track(3f, knobY - 3.4f)
            track(knobY + 3.4f, 21f)
            // Knob: a rounded pill wider than the track.
            rect.set(cx - 2.6f * unit, top + (knobY - 2.1f) * unit, cx + 2.6f * unit, top + (knobY + 2.1f) * unit)
            canvas.drawRoundRect(rect, 2.1f * unit, 2.1f * unit, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
