package com.takekazex.hypertweak.hook.rules.systemui.island.model

/** Host orientation resolved once per measurement; unknown/undefined never qualifies for a layout. */
internal enum class IslandOrientation { PORTRAIT, LANDSCAPE, UNDEFINED }

/** Insets that shrink the usable row; all values are host pixels. */
internal data class SafeInsets(val left: Int, val right: Int) {
    companion object { val NONE = SafeInsets(0, 0) }
}

/** Immutable measurement scene. No Android object crosses this boundary. */
internal data class IslandScene(
    val screenWidth: Int,
    val screenHeight: Int,
    val clockWidth: Int,
    val batteryWidth: Int,
    val gap: Int,
    val cutoutWidth: Int,
    val maxWidth: Int,
    val safe: SafeInsets,
    val orientation: IslandOrientation,
) {
    /** Horizontal room left after reserving the larger edge for every full row. */
    val rowAvailable: Int
        get() = screenWidth - 2 * maxOf(clockWidth, batteryWidth, safe.left, safe.right, 0)
}
