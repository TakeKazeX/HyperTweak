package com.takekazex.hypertweak.settings.contract

/** Persisted contract; values remain independent of storage and host implementations. */
object AodSettingsKeys {
    const val KEY_AOD_FULLSCREEN = "support_aod_fullscreen"
    const val KEY_AOD_BATTERY_ICON_VISIBLE = "aod_battery_icon_visible"
    const val KEY_AOD_DUO_ENABLED = "aod_duo_enabled"
    const val KEY_AOD_BATTERY_PERCENT_VISIBLE = "aod_battery_percent_visible"
    const val KEY_AOD_PERCENT_POSITION = "aod_percent_position"
    const val KEY_AOD_DUO_CENTER_CONTENT = "aod_duo_center_content"
    const val AOD_PERCENT_POSITION_LEFT = 0
    const val AOD_PERCENT_POSITION_RIGHT = 1
    const val AOD_PERCENT_POSITION_DUO_CENTER = 2
    const val AOD_DUO_CENTER_BATTERY = 0
    const val AOD_DUO_CENTER_SIGNAL = 1
}
