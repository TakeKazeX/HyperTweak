package com.takekazex.hypertweak.hook.rules.systemui.icon

import kotlin.math.roundToInt

/** Independent row packing in native child order; alpha never determines occupied space. */
internal object TwoRowIconLayout {
    /** Rotation swaps the available dimensions; tablets and tiny windows keep native geometry. */
    fun supportsPhoneHeader(widthDp: Int, heightDp: Int): Boolean =
        minOf(widthDp, heightDp) in 300..599

    data class Anchors(val upperRight: Float, val lowerRight: Float,
                       val firstCenter: Float, val secondCenter: Float)

    /** Current sibling bounds define the first row; ancestor transforms never enter row targets. */
    fun anchors(batteryLeft: Int, batteryTop: Int, batteryWidth: Int, batteryHeight: Int,
                containerLeft: Int, containerTop: Int, rowSpacing: Float): Anchors {
        val right = (batteryLeft - containerLeft).toFloat()
        val center = batteryTop + batteryHeight / 2f - containerTop
        return Anchors(right, right + batteryWidth, center, center + rowSpacing)
    }

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
