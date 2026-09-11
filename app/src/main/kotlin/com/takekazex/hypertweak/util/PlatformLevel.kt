package com.takekazex.hypertweak.util

import android.os.Build

/**
 * Platform-level gates.
 *
 * The supported floor is HyperOS OS3 (Android 16 / API 36, enforced by `minSdk`); OS4 ships
 * Android 17 (API 37). On OS4 the predictive-back Shell pipeline is broken at the platform level —
 * the `services.jar` task functions it depends on are gutted — so the AOSP back gesture feature is
 * hidden from the UI and force-disabled there (see `docs/FEATURE_DETAIL.md`, "AOSP back gesture").
 */
object PlatformLevel {
    /** The oldest supported platform: HyperOS OS3. */
    const val ANDROID_16_API_LEVEL = 36
    const val ANDROID_17_API_LEVEL = 37

    /** HyperOS OS4 (Android 17 / API 37) or newer. */
    val isOs4: Boolean
        get() = Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
}
