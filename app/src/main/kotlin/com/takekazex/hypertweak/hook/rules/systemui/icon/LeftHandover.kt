package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoPanelGeometry

/**
 * Hand-over curves for 图标左置 while the control center is dragged open.
 *
 * The moved slots are handed over like Duo's middle Wi-Fi glyph: an independent overlay carries the
 * icon for the whole drag, and the real control-center endpoint is revealed only at the end. Leaving
 * both host copies visible is the reported "左右同时出现两个一样的图标"; moving host children
 * directly fights the header's own Folme/layout transforms.
 *
 * [travel] and [DuoPanelGeometry] use the same linear, per-frame fraction as DuoPanelMotion. The
 * overlay stays opaque through the first 75% and the endpoint takes over during the last 25%, so a
 * reversing drag retraces the same path without a jump.
 *
 * Pure: the view work lives in `LeftContainerHooker`.
 */
internal object LeftHandover {
    /** The overlay's center travels across the complete host expansion fraction. */
    fun travel(progress: Float): Float = progress.coerceIn(0f, 1f)

    /** Overlay opacity fraction, shared with Duo's middle-network handover. */
    fun overlayFraction(progress: Float): Float = DuoPanelGeometry.overlayFraction(progress)

    /** Native endpoint opacity fraction, shared with Duo's last-quarter handover. */
    fun destinationAlpha(progress: Float): Float = DuoPanelGeometry.handoff(progress)

    /** The source remains until the overlay has produced its first frame. */
    fun sourceAlpha(progress: Float, frameReady: Boolean = true): Float =
        if (progress > 0f && frameReady) 0f else 1f

    /**
     * True while the shade owns part of the hand-over. At 0 the control center is closed and the
     * left container is the only owner again.
     */
    fun handedOver(progress: Float): Boolean = progress > 0f
}
