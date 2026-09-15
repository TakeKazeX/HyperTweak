package com.takekazex.hypertweak.util

import android.os.Build

/**
 * Platform-level gates.
 *
 * The supported floor is HyperOS OS3 (Android 16 / API 36, enforced by `minSdk`); OS4 ships
 * Android 17 (API 37). OS4 changes several internals the module hooks — system_server transition
 * handling, SystemUI navigation-bar state, and the launcher's native gesture stack — so features
 * with an OS-specific implementation branch on [isOs4] rather than assuming the API 36 shape.
 */
object PlatformLevel {
    /** The oldest supported platform: HyperOS OS3. */
    const val ANDROID_16_API_LEVEL = 36
    const val ANDROID_17_API_LEVEL = 37

    /** HyperOS OS4 (Android 17 / API 37) or newer. */
    val isOs4: Boolean
        get() = Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
}
