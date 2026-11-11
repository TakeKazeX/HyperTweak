package com.takekazex.hypertweak.settings.contract

/**
 * Native Dock glass geometry in logical pixels, inside the Hotseat area. Stored and
 * published packed as height | margin << 8 | bottom << 16 | radius << 24; bit 7 of the
 * height byte is [adaptive]. The launcher payload's `kNativeDockGlassDefault` must equal
 * [DEFAULT_PACKED] and its `kNativeDockGlassAdaptive` must equal [ADAPTIVE_BIT].
 *
 * [adaptive] narrows the glass by one Hotseat cell per icon below a full row, half on each
 * side; an empty row keeps one cell. It replaces [margin] with the default full-row margin,
 * see [effectiveMargin].
 */
internal data class NativeDockGlassGeometry(
    val height: Int, val margin: Int, val bottom: Int, val radius: Int, val adaptive: Boolean = false,
) {
    init {
        require(height in HEIGHT && margin in MARGIN && bottom in BOTTOM && radius in RADIUS) { "invalid Dock glass geometry" }
    }

    /** Side margin the launcher applies to a full row. */
    val effectiveMargin: Int get() = if (adaptive) DEFAULT.margin else margin

    fun pack(): Int = height or (if (adaptive) ADAPTIVE_BIT else 0) or (margin shl 8) or (bottom shl 16) or (radius shl 24)

    companion object {
        const val KEY = PlatformSettingsKeys.KEY_NATIVE_DOCK_GLASS
        const val ADAPTIVE_BIT = 0x80
        /** 0 fills the area above the bottom offset. */
        val HEIGHT = 0..120
        val MARGIN = 0..64
        val BOTTOM = 0..48
        val RADIUS = 0..64
        // Tuned on device inside the 102 dp phone Hotseat area of HyperOS 4.
        val DEFAULT = NativeDockGlassGeometry(height = 95, margin = 5, bottom = 5, radius = 24)
        val DEFAULT_PACKED: Int = DEFAULT.pack()

        fun unpack(value: Int): NativeDockGlassGeometry? = runCatching {
            NativeDockGlassGeometry(value and 0x7f, (value ushr 8) and 0xff, (value ushr 16) and 0xff, value ushr 24,
                adaptive = value and ADAPTIVE_BIT != 0)
        }.getOrNull()
    }
}
