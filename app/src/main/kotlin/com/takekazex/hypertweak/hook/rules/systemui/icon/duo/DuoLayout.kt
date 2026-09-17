package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import kotlin.math.abs
import kotlin.math.roundToInt

/** Pixel geometry in the current parent, deliberately independent of the hidden battery bounds. */
internal object DuoLayout {
    /** Authored glyph height the host expects, and the sliders' centred default. */
    const val DEFAULT_ICON_SIZE_DP = 24

    const val MIN_ICON_SIZE_DP = 16
    const val MAX_ICON_SIZE_DP = 32

    /** Drag distance around the default that snaps back to it, so the middle is a detent. */
    private const val SNAP_DP = 0.6f

    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

    /** Clamps a stored size; a missing or non-finite value falls back to the default. */
    fun safeSizeDp(value: Float): Float =
        value.takeIf { it.isFinite() }
            ?.coerceIn(MIN_ICON_SIZE_DP.toFloat(), MAX_ICON_SIZE_DP.toFloat())
            ?: DEFAULT_ICON_SIZE_DP.toFloat()

    /** Snaps a dragged value to the default so the middle of the slider is the untouched size. */
    fun snapSizeDp(value: Float): Int {
        val clamped = value.coerceIn(MIN_ICON_SIZE_DP.toFloat(), MAX_ICON_SIZE_DP.toFloat())
        return if (abs(clamped - DEFAULT_ICON_SIZE_DP) <= SNAP_DP) {
            DEFAULT_ICON_SIZE_DP
        } else {
            clamped.roundToInt()
        }
    }

    /**
     * The icon box edge in pixels. Both the intrinsic measurement and the explicit layout use this
     * so the reserved slot and the drawn glyph stay in step at every size.
     */
    fun iconSizePx(density: Float, sizeDp: Float): Int =
        (safeSizeDp(sizeDp) * density).roundToInt().coerceAtLeast(1)

    fun box(parentWidth: Int, parentHeight: Int, leftPadding: Int, topPadding: Int,
            rightPadding: Int, bottomPadding: Int, desired: Int, gap: Int, rtl: Boolean): Box {
        val height = minOf(desired, (parentHeight - topPadding - bottomPadding).coerceAtLeast(0))
        val width = minOf(height + gap, (parentWidth - leftPadding - rightPadding).coerceAtLeast(0))
        return Box(if (rtl) leftPadding else parentWidth - rightPadding - width,
            topPadding + (parentHeight - topPadding - bottomPadding - height) / 2, width, height)
    }
}
