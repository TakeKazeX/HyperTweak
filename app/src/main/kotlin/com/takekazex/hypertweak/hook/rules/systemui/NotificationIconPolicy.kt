package com.takekazex.hypertweak.hook.rules.systemui

/** HyperOS Chinese UI labels: urgent=4, high=3, medium=2, low=1. */
internal object NotificationIconPolicy {
    const val HYPER_OS = 0
    const val AOSP = 1
    fun normalize(mode: Int): Int = if (mode == AOSP) AOSP else HYPER_OS
    fun threshold(mode: Int): Int = if (normalize(mode) == AOSP) 3 else 4
    fun silent(importance: Int, threshold: Int, restoreAosp: Boolean): Boolean? = when {
        importance !in 0..5 -> null
        threshold >= 0 -> importance < threshold
        restoreAosp -> importance < 3
        else -> null
    }
}
