package com.takekazex.hypertweak.presentation.icon.duo

import kotlin.math.asin

/** Typography fixes the header height; the ring follows it instead of shrinking the text. */
internal object DuoTopGeometry {
    const val NUMBER_HEIGHT = 7.2f
    const val BOLT_WIDTH = DuoBoltArtwork.WIDTH
    const val BOLT_HEIGHT = DuoBoltArtwork.HEIGHT
    const val NUMBER_BOLT_GAP = 1f
    const val MAX_HEIGHT = 9f
    const val TOP_INSET = .4f
    private const val CAP_CLEARANCE = .5f

    data class Header(val number: Boolean, val bolt: Boolean, val numberWidth: Float,
                      val scale: Float, val width: Float, val height: Float,
                      val halfGapDegrees: Float) {
        val centerY get() = TOP_INSET + height / 2f
        val rawWidth get() = width / scale
    }

    /** numberWidth is an ink width already normalized to NUMBER_HEIGHT, excluding side bearings. */
    fun header(number: Boolean, charging: Boolean, numberWidth: Float,
               percentScale: Float, ringScale: Float): Header? {
        if (!number && !charging) return null
        val textWidth = if (number) numberWidth.coerceAtLeast(0f) else 0f
        val rawWidth = textWidth + (if (charging) BOLT_WIDTH else 0f) +
            (if (number && charging) NUMBER_BOLT_GAP else 0f)
        val rawHeight = if (number) NUMBER_HEIGHT else BOLT_HEIGHT
        val scale = minOf(percentScale.coerceIn(.5f, 1.5f), MAX_HEIGHT / rawHeight)
        val width = rawWidth * scale
        val halfChord = width / (2f * ringScale.coerceIn(.5f, 1.5f)) +
            DuoDrawable.TRACK_STROKE / 2f + CAP_CLEARANCE
        val angle = Math.toDegrees(asin((halfChord / DuoDrawable.TRACK_RADIUS).coerceIn(0f, .94f)).toDouble()).toFloat()
        return Header(number, charging, textWidth, scale, width, rawHeight * scale, angle)
    }
}
