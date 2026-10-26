package com.takekazex.hypertweak.hook.rules.aod

import com.takekazex.hypertweak.settings.contract.AodSettingsKeys

import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences

// Published on settings acceptance; hook callbacks perform no storage reads.
private val settingAodDuoEnabled by hookSetting(false) { Preferences.getBoolean(AodSettingsKeys.KEY_AOD_DUO_ENABLED, false) }
private val settingAodDuoCenterContent by hookSetting(AodSettingsKeys.AOD_DUO_CENTER_BATTERY) {
    Preferences.getInt(AodSettingsKeys.KEY_AOD_DUO_CENTER_CONTENT, AodSettingsKeys.AOD_DUO_CENTER_BATTERY)
}
private val settingAodPercentPosition by hookSetting(AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT) {
    Preferences.getInt(AodSettingsKeys.KEY_AOD_PERCENT_POSITION, AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT)
}
private val settingAodBatteryIconVisible by hookSetting(true) { Preferences.getBoolean(AodSettingsKeys.KEY_AOD_BATTERY_ICON_VISIBLE, true) }
private val settingAodBatteryPercentVisible by hookSetting(true) {
    Preferences.getBoolean(AodSettingsKeys.KEY_AOD_BATTERY_PERCENT_VISIBLE, true)
}

internal fun prepareAodIconSettingsSettings() { settingAodDuoEnabled }

/** One decision shared by the AOD plugin row and SystemUI's transitioning keyguard row. */
internal data class AodIconSettings(
    val batteryIcon: Boolean,
    val duo: Boolean,
    val percent: Boolean,
    val centerSignal: Boolean,
    val percentPosition: Int
) {
    val showRow: Boolean get() = batteryIcon || duo || percent
    val centerBattery: Boolean get() = !centerSignal
    val customized: Boolean get() = !batteryIcon || duo || !percent ||
        percentPosition != AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT

    companion object {
        /** The all-default configuration, used wherever a lit lock screen must ignore AOD options. */
        fun defaults(): AodIconSettings = AodIconSettings(
            batteryIcon = true,
            duo = false,
            percent = true,
            centerSignal = false,
            percentPosition = AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT
        )

        fun current(): AodIconSettings {
            val duo = settingAodDuoEnabled
            val centerSignal = settingAodDuoCenterContent == AodSettingsKeys.AOD_DUO_CENTER_SIGNAL
            val position = settingAodPercentPosition.coerceIn(AodSettingsKeys.AOD_PERCENT_POSITION_LEFT, AodSettingsKeys.AOD_PERCENT_POSITION_DUO_CENTER)
            return AodIconSettings(
                batteryIcon = settingAodBatteryIconVisible,
                duo = duo,
                percent = settingAodBatteryPercentVisible,
                centerSignal = centerSignal,
                percentPosition = if (centerSignal && position == AodSettingsKeys.AOD_PERCENT_POSITION_DUO_CENTER)
                    AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT else position
            )
        }
    }
}
