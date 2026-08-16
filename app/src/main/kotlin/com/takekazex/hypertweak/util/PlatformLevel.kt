package com.takekazex.hypertweak.util

import android.os.Build

/**
 * Platform-level gates. HyperOS OS4 ships Android 17 (API 37). On OS4 the predictive-back
 * Shell pipeline is broken at the platform level (the services.jar task functions it depends
 * on are gutted — community-verified, see `wip/os4-backgesture-experiments`), so the AOSP
 * back gesture feature is hidden from the UI and force-disabled there.
 */
object PlatformLevel {
    const val ANDROID_17_API_LEVEL = 37

    /** HyperOS OS4 (Android 17 / API 37) or newer. */
    val isOs4: Boolean
        get() = Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
}
