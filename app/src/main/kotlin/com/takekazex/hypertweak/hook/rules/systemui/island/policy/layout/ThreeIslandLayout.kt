package com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout

/** All dimensions are host pixels. Keep the centre anchored and position each side using its own width. */
internal data class ThreeIslandLayout(
    val bigX: Int, val bigWidth: Int, val leftX: Int, val rightX: Int,
    val full: Boolean, val screenWidth: Int,
) {
    companion object {
        fun calculate(
            screenWidth: Int, screenHeight: Int, clockWidth: Int, batteryWidth: Int,
            requestedBigWidth: Int, minimumBigWidth: Int, smallWidth: Int, gap: Int,
            safeLeft: Int = 0, safeRight: Int = 0, rightSmallWidth: Int = smallWidth,
        ): ThreeIslandLayout? {
            if (screenWidth <= 0 || screenHeight <= 0 || smallWidth <= 0 || rightSmallWidth <= 0 || gap < 0 ||
                clockWidth < 0 || batteryWidth < 0 || minimumBigWidth <= 0) return null
            val edge = maxOf(clockWidth, batteryWidth, safeLeft, safeRight).coerceAtLeast(0)
            val available = screenWidth.toLong() - 2L * (edge.toLong() + maxOf(smallWidth, rightSmallWidth) + gap)
            if (available < minimumBigWidth) return null
            val width = minOf(requestedBigWidth.toLong(), available).toInt()
            if (width < minimumBigWidth) return null
            val x = (screenWidth - width) / 2
            return ThreeIslandLayout(x, width, x - gap - smallWidth, x + width + gap, false, screenWidth)
        }
    }
}
