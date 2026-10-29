package com.takekazex.hypertweak.hook.nativebridge

/** Native first preset is zero/transparent; use its actual blue preset when no opaque color exists. */
internal object LauncherIconThemeColors {
    const val NATIVE_BLUE: Int = -2563588 // 0xffd8e1fc, verified host preset
    fun nativeColor(original: String?): Int {
        val value = original?.toLongOrNull()?.takeIf { it in Int.MIN_VALUE.toLong()..0xffffffffL }?.toInt()
        return value?.takeIf { it ushr 24 == 255 } ?: NATIVE_BLUE
    }
}
