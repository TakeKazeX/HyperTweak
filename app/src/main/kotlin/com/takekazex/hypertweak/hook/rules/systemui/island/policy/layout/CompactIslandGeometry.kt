package com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandResultGeometry

/** Values read from one native phone-helper call. All dimensions are host pixels. */
internal data class CompactIslandInput(
    val screenWidth: Int,
    val screenHeight: Int,
    val clock: Int,
    val battery: Int,
    val cutout: Int,
    val maxWidth: Int,
    val space: Int,
    val smallWidth: Int,
    val content: IslandContentSize,
    val margin: Int,
    val safeLeft: Int = 0,
    val safeRight: Int = 0,
)

/**
 * Portrait main template without the mirrored placeholder: each side keeps its own measured
 * width (a long left side is narrowed to its seat) while the camera stays at the screen centre.
 *
 * The result depends only on the native input of this one measurement. It does not depend on
 * which state handler currently owns the view, so the host's enter, exit and expand animations
 * read the same compact fields that the visible island was laid out with.
 */
internal object CompactIslandGeometry {
    /** Null keeps the native result: the compact island would not fit beside the clock, battery or small island. */
    fun calculate(input: CompactIslandInput): IslandResultGeometry? {
        if (input.smallWidth < 0 || input.space < 0) return null
        // The host's own maximum width also only narrows the left side, never below the right.
        val capped = if (input.maxWidth <= 0) input.content else input.content.let {
            val left = minOf(it.left, input.maxWidth - input.cutout - it.right)
            if (left < minOf(it.left, it.right)) return null
            IslandContentSize(left, it.right)
        }
        val content = PortraitIslandLayout.fit(
            input.screenWidth, input.screenHeight, input.clock, input.battery, capped, input.cutout,
            small = 0, gap = input.space, leftSmall = false, rightSmall = input.smallWidth > 0,
            safeLeft = input.safeLeft, safeRight = input.safeRight, rightSmallWidth = input.smallWidth,
        ) ?: return null
        val layout = PortraitIslandLayout.calculate(
            input.screenWidth, input.screenHeight, input.clock, input.battery, content, input.cutout,
            small = 0, gap = input.space, leftSmall = false, rightSmall = input.smallWidth > 0,
            safeLeft = input.safeLeft, safeRight = input.safeRight, rightSmallWidth = input.smallWidth,
        ) ?: return null
        if (input.maxWidth > 0 && layout.bigWidth > input.maxWidth) return null
        return IslandResultGeometry(
            width = layout.bigWidth,
            left = content.left,
            right = content.right,
            x = layout.bigX,
            margin = input.margin,
            viewWidth = layout.bigWidth,
            viewLeft = content.left,
            viewRight = content.right,
            viewX = layout.bigX,
        )
    }

    /** True for a camera-seated main template whose two sides differ, which needs a content offset. */
    fun asymmetric(geometry: IslandResultGeometry): Boolean =
        geometry.width - geometry.left - geometry.right > 0 && geometry.left != geometry.right

    /**
     * Offset added to the host's own content translation. The native view centres its content;
     * the compact clip is centred on the camera instead.
     */
    fun translation(geometry: IslandResultGeometry, screenWidth: Int): Float =
        (2f * geometry.x + geometry.width - screenWidth) / 2f
}
