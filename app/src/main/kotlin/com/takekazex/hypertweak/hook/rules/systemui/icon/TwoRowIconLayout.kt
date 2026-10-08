package com.takekazex.hypertweak.hook.rules.systemui.icon

import kotlin.math.roundToInt

/** Independent row packing in native child order; alpha never determines occupied space. */
internal object TwoRowIconLayout {
    data class Geometry(val firstCenter: Float, val secondCenter: Float, val minimumHeight: Int)

    /** SIM count changes content, never the two-row header's reserved geometry. */
    fun geometry(visibleRowHeights: List<Int>, fallbackHeight: Int, gap: Int,
                 paddingTop: Int = 0, paddingBottom: Int = 0): Geometry {
        val height = maxOf(visibleRowHeights.maxOrNull() ?: 0, fallbackHeight, 1)
        val spacing = gap.coerceAtLeast(0)
        val first = paddingTop + height / 2f
        return Geometry(first, first + height + spacing, paddingTop + paddingBottom + height * 2 + spacing)
    }

    /** Pin each visible carrier to the same anchors as the battery and status icons. */
    fun carrierRowTops(visibleRows: List<Pair<Int, Int>>, geometry: Geometry): Map<Int, Int> =
        visibleRows.mapIndexed { index, (slot, height) ->
            val center = if (index == 0) geometry.firstCenter else geometry.secondCenter
            slot to (center - height / 2f).roundToInt()
        }.toMap()

    fun visibleRowMargins(slots: List<Int>, gap: Int): Map<Int, Int> =
        slots.mapIndexed { index, slot -> slot to if (index == 0) 0 else gap.coerceAtLeast(0) }.toMap()

    data class Item(val index: Int, val width: Float, val upper: Boolean)

    fun positions(items: List<Item>, upperRight: Float, lowerRight: Float,
                  spacing: Float, rtl: Boolean): Map<Int, Float> {
        val result = LinkedHashMap<Int, Float>()
        for (upper in listOf(true, false)) {
            var cursor = if (upper) upperRight else lowerRight
            val row = items.filter { it.upper == upper }.sortedBy { it.index }
            for (item in if (rtl) row else row.asReversed()) {
                cursor -= item.width.coerceAtLeast(0f)
                result[item.index] = cursor
                cursor -= spacing.coerceAtLeast(0f)
            }
        }
        return result
    }
}
