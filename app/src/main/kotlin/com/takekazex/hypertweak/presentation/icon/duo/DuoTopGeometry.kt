package com.takekazex.hypertweak.presentation.icon.duo

import kotlin.math.asin
import kotlin.math.cos

/** Fit the opening to its content, placing circular caps just below the number's visual centre. */
internal object DuoTopGeometry {
    const val NUMBER_HEIGHT = 7.2f
    const val BOLT_WIDTH = DuoBoltArtwork.WIDTH
    const val BOLT_HEIGHT = DuoBoltArtwork.HEIGHT
    const val NUMBER_BOLT_GAP = 1.3f
    const val MAX_HEIGHT = 9f
    const val MAX_NUMBER_BOTTOM = 9.35f
    const val NUMBER_CAP_OFFSET = .6f
    // Same upper ink edge as the standalone bolt; the compact transform provides headroom.
    const val TOP_INSET = DuoDrawable.CENTER - DuoDrawable.TRACK_RADIUS - BOLT_HEIGHT / 2f
    private const val CAP_CLEARANCE = .9f

    data class Header(val number: Boolean, val bolt: Boolean, val numberWidth: Float,
                      val scale: Float, val width: Float, val height: Float,
                      val halfGapDegrees: Float, val centerY: Float) {
        val rawWidth get() = width / scale
    }

    /** numberWidth is a tabular advance width normalized to NUMBER_HEIGHT, including side bearings. */
    fun header(number: Boolean, charging: Boolean, numberWidth: Float,
               percentScale: Float, ringScale: Float): Header? {
        if (!number && !charging) return null
        val textWidth = if (number) numberWidth.coerceAtLeast(0f) else 0f
        val rawWidth = textWidth + (if (charging) BOLT_WIDTH else 0f) +
            (if (number && charging) NUMBER_BOLT_GAP else 0f)
        val rawHeight = if (number) NUMBER_HEIGHT else BOLT_HEIGHT
        var scale = minOf(percentScale.coerceIn(.5f, 1.5f), MAX_HEIGHT / rawHeight)
        val ring = ringScale.coerceIn(.5f, 1.5f)
        fun halfGap(atScale: Float): Float {
            val halfChord = rawWidth * atScale / (2f * ring) + DuoDrawable.TRACK_STROKE / 2f + CAP_CLEARANCE
            return asin((halfChord / DuoDrawable.TRACK_RADIUS).coerceIn(0f, .94f))
        }
        fun capY(atScale: Float) = DuoDrawable.CENTER - DuoDrawable.TRACK_RADIUS * ring * cos(halfGap(atScale))
        // A wide 100 + bolt header must leave room for the network glyph below its new baseline.
        // Fit only when necessary, preserving the silhouette and natural digit proportions.
        if (number && capY(scale) - NUMBER_CAP_OFFSET + rawHeight * scale / 2f > MAX_NUMBER_BOTTOM) {
            var low = .5f
            var high = scale
            repeat(10) {
                val candidate = (low + high) / 2f
                if (capY(candidate) - NUMBER_CAP_OFFSET + rawHeight * candidate / 2f <= MAX_NUMBER_BOTTOM) low = candidate else high = candidate
            }
            scale = low
        }
        val width = rawWidth * scale
        val angle = Math.toDegrees(halfGap(scale).toDouble()).toFloat()
        // Follow the caps as the natural width changes, rather than pinning text to the viewport.
        val centerY = if (number) maxOf(TOP_INSET + rawHeight * scale / 2f,
            capY(scale) - NUMBER_CAP_OFFSET) else
            DuoDrawable.CENTER - DuoDrawable.TRACK_RADIUS * ring
        return Header(number, charging, textWidth, scale, width, rawHeight * scale, angle, centerY)
    }
}
