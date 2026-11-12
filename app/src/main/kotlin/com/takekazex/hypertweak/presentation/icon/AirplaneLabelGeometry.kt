package com.takekazex.hypertweak.presentation.icon

import com.takekazex.hypertweak.settings.contract.AirplaneLabelMode
import kotlin.math.ceil

/**
 * Where the airplane glyph sits inside a carrier label. Shared by the live text span and its tests.
 *
 * The glyph spans the text's full line box (ascent to descent), so the row height never changes. The
 * host vector already carries side padding of about 15% of its width, so [gapPx] adds only a little
 * separation from the label.
 */
object AirplaneLabelGeometry {
    const val GAP_DP = 1f

    /** Logical order of the glyph and the label text inside the label. */
    enum class Order { GLYPH_ONLY, GLYPH_FIRST, TEXT_FIRST }

    data class Box(
        /** Horizontal advance of the replaced run, gap included. */
        val advance: Int,
        /** Glyph offset from the start of the run, in visual (left-to-right) coordinates. */
        val glyphX: Float,
        /** Glyph top relative to the baseline. */
        val glyphTop: Float,
        val glyphWidth: Float,
        val glyphHeight: Float
    )

    fun order(mode: AirplaneLabelMode): Order? = when (mode) {
        AirplaneLabelMode.ICON -> Order.GLYPH_ONLY
        AirplaneLabelMode.ICON_START -> Order.GLYPH_FIRST
        AirplaneLabelMode.ICON_END -> Order.TEXT_FIRST
        AirplaneLabelMode.SYSTEM, AirplaneLabelMode.HIDE -> null
    }

    /**
     * [ascent] is negative and [descent] positive, as in `Paint.FontMetrics`. In RTL text the
     * logically first run is drawn on the right, so the gap side flips with [rtl].
     */
    fun box(
        ascent: Float,
        descent: Float,
        intrinsicWidth: Int,
        intrinsicHeight: Int,
        gapPx: Float,
        order: Order,
        rtl: Boolean
    ): Box {
        val height = (descent - ascent).coerceAtLeast(0f)
        val ratio = if (intrinsicWidth > 0 && intrinsicHeight > 0) intrinsicWidth.toFloat() / intrinsicHeight else 1f
        val width = height * ratio
        val gap = if (order == Order.GLYPH_ONLY) 0f else gapPx.coerceAtLeast(0f)
        // The gap faces the label: after the glyph when the glyph is visually left of the text.
        val glyphOnLeft = (order == Order.GLYPH_FIRST) != rtl
        val glyphX = if (order == Order.GLYPH_ONLY || glyphOnLeft) 0f else gap
        return Box(ceil(width + gap).toInt(), glyphX, ascent, width, height)
    }
}
