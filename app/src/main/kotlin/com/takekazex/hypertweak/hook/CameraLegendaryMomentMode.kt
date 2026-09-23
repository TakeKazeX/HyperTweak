package com.takekazex.hypertweak.hook

/** Selection for the camera's shared Legendary/Leica Moment entry. */
internal object CameraLegendaryMomentMode {

    const val MODE_OFF = "off"
    const val MODE_LEICA = "leica"
    const val MODE_LEGENDARY = "legendary"

    const val DEFAULT = MODE_OFF

    val MODES = listOf(MODE_OFF, MODE_LEICA, MODE_LEGENDARY)

    /** Only Legendary Moment needs the Madrid profile; Leica Moment leaves the native config intact. */
    fun targetProfileClassName(mode: String?): String? = when (mode) {
        MODE_LEGENDARY -> "com.mi.device.Madrid"
        else -> null
    }

    /** Device codename associated with the optional Madrid profile. */
    fun targetDeviceCodename(mode: String?): String? = when (mode) {
        MODE_LEGENDARY -> "madrid"
        else -> null
    }

    fun parse(raw: String?): String? = raw?.trim()?.takeIf { it in MODES }

    /** An old enabled Boolean migrates to the existing Leica Moment choice. */
    fun resolve(stored: String?, legacyEnable: Boolean?): String {
        parse(stored)?.let { return it }
        if (stored != null) return DEFAULT
        return if (legacyEnable == true) MODE_LEICA else MODE_OFF
    }

    fun index(mode: String?): Int = MODES.indexOf(mode).coerceAtLeast(0)

    fun fromIndex(index: Int): String = MODES[index.coerceIn(MODES.indices)]
}
