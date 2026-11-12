package com.takekazex.hypertweak.settings.contract

/** Stable dropdown values. Lockscreen carrier labels have their own independent switches. */
internal object SimVisibilityPolicy {
    const val DEFAULT = 0
    const val STATUS_BAR_ONLY = 1
    const val ALL = 2

    fun mode(slot: Int, isNonDefault: Boolean, one: Int, two: Int, nonDefault: Int): Int =
        maxOf(when (slot) { 0 -> one; 1 -> two; else -> DEFAULT },
            if (isNonDefault) nonDefault else DEFAULT)

    fun hides(mode: Int, surface: IconSurface): Boolean = when (surface) {
        IconSurface.STATUS_BAR -> mode == STATUS_BAR_ONLY || mode == ALL
        IconSurface.CONTROL_CENTER -> mode == ALL
        else -> false
    }
}
