package com.takekazex.hypertweak.presentation.icon.duo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.graphics.withSave

/** Owns header paint and ink metrics; network labels never inherit its alignment or text size. */
internal class DuoTopRenderer {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 700, false)
        textSize = REFERENCE_TEXT_SIZE
        textAlign = Paint.Align.LEFT
        style = Paint.Style.FILL
    }
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ink = Rect()
    // One natural font size for every value. Preserve digit overshoots and normal width.
    private val numberGlyphScale = Rect().let { zero ->
        textPaint.getTextBounds("0", 0, 1, zero)
        DuoTopGeometry.NUMBER_HEIGHT / zero.height().coerceAtLeast(1)
    }
    private val bolt = DuoBoltArtwork.path()

    data class Layout(val geometry: DuoTopGeometry.Header, val text: String,
                      val glyphScale: Float, val inkLeft: Int, val inkCenterY: Float)

    fun measure(percent: Int, number: Boolean, charging: Boolean, sizes: DuoSizes): Layout? {
        if (!number && !charging) return null
        val text = if (number) percent.toString() else ""
        textPaint.getTextBounds(text, 0, text.length, ink)
        val glyphScale = numberGlyphScale
        val geometry = DuoTopGeometry.header(number, charging, ink.width() * glyphScale,
            sizes.percent, sizes.ring) ?: return null
        return Layout(geometry, text, glyphScale, ink.left, (ink.top + ink.bottom) / 2f)
    }

    fun draw(canvas: Canvas, layout: Layout, foreground: Int, opacity: Int) = canvas.withSave {
        val geometry = layout.geometry
        translate(DuoDrawable.CENTER, geometry.centerY)
        scale(geometry.scale, geometry.scale)
        val alpha = Color.alpha(foreground) * opacity / 255
        var x = -geometry.rawWidth / 2f
        if (geometry.number) {
            textPaint.color = foreground
            textPaint.alpha = alpha
            withSave {
                translate(x, 0f)
                scale(layout.glyphScale, layout.glyphScale)
                drawText(layout.text, -layout.inkLeft.toFloat(), -layout.inkCenterY, textPaint)
            }
            x += geometry.numberWidth + if (geometry.bolt) DuoTopGeometry.NUMBER_BOLT_GAP else 0f
        }
        if (geometry.bolt) {
            boltPaint.color = foreground
            boltPaint.alpha = alpha
            translate(x + DuoTopGeometry.BOLT_WIDTH / 2f, 0f)
            drawPath(bolt, boltPaint)
        }
    }

    // Measure at high resolution: integer bounds at 12px can lose a quarter of a digit's ink height.
    private companion object { const val REFERENCE_TEXT_SIZE = 256f }
}
