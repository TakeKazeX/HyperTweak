package com.takekazex.hypertweak.hook.rules.systemui.island

/** Content can be asymmetric, while the physical camera seat stays at the screen centre. */
internal object PortraitIslandLayout {
    fun calculate(screenWidth: Int, screenHeight: Int, clock: Int, battery: Int,
        content: IslandContentSize, cutout: Int, small: Int, gap: Int, leftSmall: Boolean,
        rightSmall: Boolean, safeLeft: Int = 0, safeRight: Int = 0): ThreeIslandLayout? {
        if (screenWidth <= 0 || screenHeight <= 0 || clock < 0 || battery < 0 || cutout < 0 ||
            small < 0 || gap < 0 || content.left <= 0 || content.right <= 0) return null
        val cameraLeft = (screenWidth - cutout) / 2
        val x = cameraLeft.toLong() - content.left
        val width = content.left.toLong() + cutout + content.right
        val left = x - if (leftSmall) small.toLong() + gap else 0L
        val right = x + width + if (rightSmall) small.toLong() + gap else 0L
        if (left < maxOf(clock, safeLeft, 0) || right > screenWidth.toLong() - maxOf(battery, safeRight, 0) ||
            width > Int.MAX_VALUE) return null
        return ThreeIslandLayout(x.toInt(), width.toInt(), (x - gap - small).toInt(),
            (x + width + gap).toInt(), false, screenWidth)
    }
}
