package com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout

/** Content can be asymmetric, while the physical camera seat stays at the screen centre. */
internal object PortraitIslandLayout {
    fun calculate(screenWidth: Int, screenHeight: Int, clock: Int, battery: Int,
        content: IslandContentSize, cutout: Int, small: Int, gap: Int, leftSmall: Boolean,
        rightSmall: Boolean, safeLeft: Int = 0, safeRight: Int = 0,
        rightSmallWidth: Int = small): ThreeIslandLayout? {
        val fitted = fit(screenWidth, screenHeight, clock, battery, content, cutout, small, gap, leftSmall,
            rightSmall, safeLeft, safeRight, rightSmallWidth) ?: return null
        val x = (screenWidth - cutout) / 2 - fitted.left
        val width = fitted.left + cutout + fitted.right
        return ThreeIslandLayout(x, width, x - gap - small, x + width + gap, false, screenWidth)
    }

    /**
     * The content actually seated beside the camera. A long left side (e.g. a file name) is
     * narrowed to the room left of the camera, never below the right side's width, and the
     * host truncates its text; the right side is never squeezed. Null when it cannot fit.
     */
    fun fit(screenWidth: Int, screenHeight: Int, clock: Int, battery: Int,
        content: IslandContentSize, cutout: Int, small: Int, gap: Int, leftSmall: Boolean,
        rightSmall: Boolean, safeLeft: Int = 0, safeRight: Int = 0,
        rightSmallWidth: Int = small): IslandContentSize? {
        if (screenWidth <= 0 || screenHeight <= 0 || clock < 0 || battery < 0 || cutout < 0 ||
            small < 0 || rightSmallWidth < 0 || gap < 0 || content.left <= 0 || content.right <= 0) return null
        val cameraLeft = (screenWidth - cutout) / 2
        val room = cameraLeft.toLong() - maxOf(clock, safeLeft, 0) - if (leftSmall) small.toLong() + gap else 0L
        val left = minOf(content.left.toLong(), room)
        if (left < minOf(content.left, content.right)) return null
        val right = cameraLeft.toLong() + cutout + content.right + if (rightSmall) rightSmallWidth.toLong() + gap else 0L
        if (right > screenWidth.toLong() - maxOf(battery, safeRight, 0)) return null
        return IslandContentSize(left.toInt(), content.right)
    }
}
