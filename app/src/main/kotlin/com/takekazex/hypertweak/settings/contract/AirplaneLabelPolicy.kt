package com.takekazex.hypertweak.settings.contract

/** How a carrier row shows the host's airplane-mode label. [storedValue] is the dropdown order. */
enum class AirplaneLabelMode(val storedValue: Int) {
    SYSTEM(0),
    HIDE(1),
    /** The label becomes the glyph alone. */
    ICON(2),
    /** The glyph precedes the label (left in LTR, right in RTL). */
    ICON_START(3),
    /** The glyph follows the label. */
    ICON_END(4);

    val drawsGlyph: Boolean get() = this == ICON || this == ICON_START || this == ICON_END

    companion object {
        val RANGE = 0..4
        fun fromStored(value: Int): AirplaneLabelMode = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

/** The host headers that show the airplane label through `MiuiCarrierTextLayout`. */
enum class AirplaneLabelSurface { CONTROL_CENTER, NOTIFICATION, KEYGUARD }

data class AirplaneLabelModes(
    val controlCenter: AirplaneLabelMode = AirplaneLabelMode.SYSTEM,
    val notification: AirplaneLabelMode = AirplaneLabelMode.SYSTEM,
    val keyguard: AirplaneLabelMode = AirplaneLabelMode.SYSTEM
) {
    operator fun get(surface: AirplaneLabelSurface): AirplaneLabelMode = when (surface) {
        AirplaneLabelSurface.CONTROL_CENTER -> controlCenter
        AirplaneLabelSurface.NOTIFICATION -> notification
        AirplaneLabelSurface.KEYGUARD -> keyguard
    }

    val customized: Boolean get() = AirplaneLabelSurface.entries.any { get(it) != AirplaneLabelMode.SYSTEM }
}

object AirplaneLabelPolicy {
    /**
     * Maps a host `MiuiCarrierTextLayout` (its view-id entry name and keyguard flag) to a surface.
     * Both flags must agree; an unknown or inconsistent layout stays native.
     */
    fun surfaceFor(layoutIdName: String?, keyguardLayout: Boolean): AirplaneLabelSurface? = when (layoutIdName) {
        "normal_control_center_carrier_layout" -> AirplaneLabelSurface.CONTROL_CENTER.takeUnless { keyguardLayout }
        "normal_notification_center_carrier_view", "notification_shade_carrier_layout" ->
            AirplaneLabelSurface.NOTIFICATION.takeUnless { keyguardLayout }
        "keyguard_carrier_layout" -> AirplaneLabelSurface.KEYGUARD.takeIf { keyguardLayout }
        else -> null
    }
}
