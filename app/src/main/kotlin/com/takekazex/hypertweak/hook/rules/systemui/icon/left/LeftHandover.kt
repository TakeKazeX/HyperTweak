package com.takekazex.hypertweak.hook.rules.systemui.icon.left


/** Pure ownership policy for the left clone's full-progress, single-image handover. */
internal object LeftHandover {
    fun repairProgress(progress: Float, hasNativeCallback: Boolean, shadeOpen: Boolean): Float =
        if (!hasNativeCallback && shadeOpen) 1f else progress.coerceIn(0f, 1f)

    /** The overlay's center travels across the complete host expansion fraction. */
    fun travel(progress: Float): Float = progress.coerceIn(0f, 1f)

    /** Keep the moving glyph until the exact native endpoint. */
    fun overlayFraction(progress: Float): Float = 1f - destinationAlpha(progress)

    /** No stationary duplicate during the last quarter of the drag. */
    fun destinationAlpha(progress: Float): Float = if (progress >= 1f) 1f else 0f

    /** The source remains until the overlay has produced its first frame. */
    fun sourceAlpha(progress: Float, frameReady: Boolean = true): Float =
        if (progress > 0f && frameReady) 0f else 1f

    /**
     * True while the shade owns part of the hand-over. At 0 the control center is closed and the
     * left container is the only owner again.
     */
    fun handedOver(progress: Float): Boolean = progress > 0f
}
