package com.takekazex.hypertweak.settings.contract

/** Independent surface, value, colour and popup-placement choices. */
data class SliderPercentagePolicy(
    val display: Int = 0,
    val values: Int = 2,
    val unifiedColor: Boolean = false,
    val popupPosition: Int = 1,
    val popupStyle: Int = 0
) {
    fun controlCenter(brightness: Boolean): Boolean =
        (display == 1 || display == 3) && if (brightness) values != 1 else values != 0

    /** Value filtering belongs to control center; the popup only has a volume value. */
    fun volumePanel(inControlCenter: Boolean): Boolean =
        if (inControlCenter) controlCenter(brightness = false) else display in 2..3

    /** Off and excluded scopes also hide native super-volume labels (for example 200%). */
    fun suppressVolumePanel(inControlCenter: Boolean): Boolean = !volumePanel(inControlCenter)

    val outside: Boolean get() = popupPosition == 1
    fun innerVolume(expanded: Boolean, inControlCenter: Boolean): Boolean =
        volumePanel(inControlCenter) && (expanded || inControlCenter || !outside)
    fun outerVolume(expanded: Boolean, inControlCenter: Boolean): Boolean =
        volumePanel(inControlCenter) && !expanded && !inControlCenter && outside

    companion object {
        fun read(boolean: (String, Boolean) -> Boolean, integer: (String, Int) -> Int): SliderPercentagePolicy = SliderPercentagePolicy(
            display = integer(SystemUiSettingsKeys.KEY_SLIDER_DISPLAY_MODE,
                if (boolean(SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE, false)) 3 else 0).coerceIn(0, 3),
            values = integer(SystemUiSettingsKeys.KEY_SLIDER_VALUE_MODE, 2).coerceIn(0, 2),
            unifiedColor = boolean(SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false),
            popupPosition = integer(SystemUiSettingsKeys.KEY_SLIDER_POPUP_POSITION, 1).coerceIn(0, 1),
            popupStyle = integer(SystemUiSettingsKeys.KEY_SLIDER_POPUP_STYLE, 0).coerceIn(0, 2)
        )
    }
}
