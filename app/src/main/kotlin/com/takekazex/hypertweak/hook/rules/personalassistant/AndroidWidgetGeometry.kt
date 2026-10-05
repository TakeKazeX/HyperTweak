package com.takekazex.hypertweak.hook.rules.personalassistant

/** Translate provider geometry using the assistant's actual native cell size. */
internal object AndroidWidgetGeometry {
    data class Span(val x: Int, val y: Int)
    fun span(targetX: Int, targetY: Int, minWidth: Int, minHeight: Int, cellPixels: Int): Span? {
        if (cellPixels <= 0 || minWidth < 0 || minHeight < 0) return null
        fun cells(target: Int, pixels: Int): Int = if (target > 0) target
            else ((pixels.toLong() + cellPixels - 1) / cellPixels).coerceAtLeast(1).toInt()
        val x = cells(targetX, minWidth)
        val y = cells(targetY, minHeight)
        // Keep the physical four-column grid bound, not the MIUI card
        // whitelist. The scrollable native layout owns height and placement.
        if (x !in 1..4 || y < 1) return null
        return Span(x, y)
    }
}
