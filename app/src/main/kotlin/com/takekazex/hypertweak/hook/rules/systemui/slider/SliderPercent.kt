package com.takekazex.hypertweak.hook.rules.systemui.slider

import kotlin.math.roundToInt

/** Percentage over the adjustable range, independent of the stream's absolute offset. */
fun volumePercent(level: Int, min: Int, max: Int): Int? {
    if (max <= min || level !in min..max) return null
    return (((level - min).toFloat() / (max - min)) * 100f)
        .roundToInt()
        .coerceIn(0, 100)
}

fun setPercentIfChanged(textView: android.widget.TextView, percent: Int): Boolean {
    val value = "$percent%"
    if (textView.text?.toString() == value) return false
    textView.text = value
    return true
}

/**
 * `View.setVisibility` / `setAlpha` run the whole hooked-setter chain in SystemUI even when the value
 * is unchanged; per-frame label paths write through these instead.
 */
internal fun android.view.View.setVisibilityIfChanged(value: Int) {
    if (visibility != value) visibility = value
}

internal fun android.view.View.setAlphaIfChanged(value: Float) {
    if (alpha != value) alpha = value
}
