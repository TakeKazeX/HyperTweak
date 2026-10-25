package com.takekazex.hypertweak.hook.rules.ime

/** Difference-based raise geometry. The controller checks padding ownership before measuring. */
internal object ImeRaisePolicy {
    fun desiredPadding(
        height: Int, contentBottom: Int, currentPadding: Int,
        captionHeight: Int, tolerance: Int
    ): Int {
        val shortfall = contentBottom + currentPadding - (height - captionHeight)
        return if (shortfall <= tolerance) 0 else minOf(shortfall, captionHeight)
    }
}
