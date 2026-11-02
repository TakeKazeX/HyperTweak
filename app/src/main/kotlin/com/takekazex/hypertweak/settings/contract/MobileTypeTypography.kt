package com.takekazex.hypertweak.settings.contract

/** Relative size keeps each native/carrier/Duo surface's own baseline. */
data class MobileTypeTypography(val enabled: Boolean = false, val sizePercent: Float = 100f, val weight: Int = 400) {
    fun safe() = copy(sizePercent = sizePercent.takeIf(Float::isFinite)?.coerceIn(50f, 150f) ?: 100f,
        weight = weight.coerceIn(100, 900))
    val scale: Float get() = if (enabled) safe().sizePercent / 100f else 1f
}
