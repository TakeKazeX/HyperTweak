package com.takekazex.hypertweak.hook.rules.systemui.island

/** All dimensions are host pixels. Reserve the same space on both sides of the cutout. */
internal data class ThreeIslandLayout(
    val bigX: Int, val bigWidth: Int, val leftX: Int, val rightX: Int,
    val full: Boolean, val screenWidth: Int,
) {
    companion object {
        fun calculate(
            screenWidth: Int, screenHeight: Int, clockWidth: Int, batteryWidth: Int,
            requestedBigWidth: Int, minimumBigWidth: Int, smallWidth: Int, gap: Int,
            safeLeft: Int = 0, safeRight: Int = 0,
        ): ThreeIslandLayout? {
            if (screenWidth <= 0 || screenHeight <= 0 || smallWidth <= 0 || gap < 0 ||
                clockWidth < 0 || batteryWidth < 0 || minimumBigWidth <= 0) return null
            val edge = maxOf(clockWidth, batteryWidth, safeLeft, safeRight).coerceAtLeast(0)
            val available = screenWidth.toLong() - 2L * (edge.toLong() + smallWidth + gap)
            if (available < minimumBigWidth) return null
            val width = minOf(requestedBigWidth.toLong(), available).toInt()
            if (width < minimumBigWidth) return null
            val x = (screenWidth - width) / 2
            return ThreeIslandLayout(x, width, x - gap - smallWidth, x + width + gap, false, screenWidth)
        }
    }
}
