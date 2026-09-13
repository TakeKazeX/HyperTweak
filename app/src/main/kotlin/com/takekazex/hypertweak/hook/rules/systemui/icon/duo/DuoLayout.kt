package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

/** Pixel geometry in the current parent, deliberately independent of the hidden battery bounds. */
internal object DuoLayout {
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)
    fun box(parentWidth: Int, parentHeight: Int, leftPadding: Int, topPadding: Int,
            rightPadding: Int, bottomPadding: Int, desired: Int, gap: Int, rtl: Boolean): Box {
        val height = minOf(desired, (parentHeight - topPadding - bottomPadding).coerceAtLeast(0))
        val width = minOf(height + gap, (parentWidth - leftPadding - rightPadding).coerceAtLeast(0))
        return Box(if (rtl) leftPadding else parentWidth - rightPadding - width,
            topPadding + (parentHeight - topPadding - bottomPadding - height) / 2, width, height)
    }
}
