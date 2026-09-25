package com.takekazex.hypertweak.hook.rules.aod

import com.takekazex.hypertweak.hook.Preferences

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
        percentPosition != Preferences.AOD_PERCENT_POSITION_RIGHT

    companion object {
        fun current(): AodIconSettings {
            val duo = Preferences.getBoolean(Preferences.KEY_AOD_DUO_ENABLED, false)
            val centerSignal = Preferences.getInt(
                Preferences.KEY_AOD_DUO_CENTER_CONTENT,
                Preferences.AOD_DUO_CENTER_BATTERY
            ) == Preferences.AOD_DUO_CENTER_SIGNAL
            val position = Preferences.getInt(
                Preferences.KEY_AOD_PERCENT_POSITION,
                Preferences.AOD_PERCENT_POSITION_RIGHT
            ).coerceIn(Preferences.AOD_PERCENT_POSITION_LEFT, Preferences.AOD_PERCENT_POSITION_DUO_CENTER)
            return AodIconSettings(
                batteryIcon = Preferences.getBoolean(Preferences.KEY_AOD_BATTERY_ICON_VISIBLE, true),
                duo = duo,
                percent = Preferences.getBoolean(Preferences.KEY_AOD_BATTERY_PERCENT_VISIBLE, true),
                centerSignal = centerSignal,
                percentPosition = if (centerSignal && position == Preferences.AOD_PERCENT_POSITION_DUO_CENTER)
                    Preferences.AOD_PERCENT_POSITION_RIGHT else position
            )
        }
    }
}
