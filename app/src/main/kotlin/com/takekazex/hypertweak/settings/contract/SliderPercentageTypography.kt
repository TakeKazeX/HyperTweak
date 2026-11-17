package com.takekazex.hypertweak.settings.contract

/**
 * Relative size over each percentage label's own host size, plus one absolute weight. With the
 * custom option off the labels still use the defaults, never the host's own face.
 */
data class SliderPercentageTypography(
    val enabled: Boolean = false,
    val sizePercent: Int = DEFAULT_SIZE_PERCENT,
    val weight: Int = DEFAULT_WEIGHT
) {
    /** What the labels are drawn with: the custom values when enabled, the defaults otherwise. */
    fun effective(): SliderPercentageTypography = if (enabled) safe() else SliderPercentageTypography()

    fun safe() = copy(sizePercent = sizePercent.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT),
        weight = weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT))

    companion object {
        const val MIN_SIZE_PERCENT = 50
        const val MAX_SIZE_PERCENT = 200
        const val DEFAULT_SIZE_PERCENT = 110
        const val MIN_WEIGHT = 100
        const val MAX_WEIGHT = 900
        const val DEFAULT_WEIGHT = 400

        fun read(boolean: (String, Boolean) -> Boolean, integer: (String, Int) -> Int) = SliderPercentageTypography(
            boolean(SystemUiSettingsKeys.KEY_SLIDER_TYPOGRAPHY, false),
            integer(SystemUiSettingsKeys.KEY_SLIDER_SIZE_PERCENT, DEFAULT_SIZE_PERCENT),
            integer(SystemUiSettingsKeys.KEY_SLIDER_WEIGHT, DEFAULT_WEIGHT)
        ).safe()
    }
}
