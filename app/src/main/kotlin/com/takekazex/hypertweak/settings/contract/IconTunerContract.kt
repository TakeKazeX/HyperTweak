package com.takekazex.hypertweak.settings.contract

data class IconTunerSnapshot(
    val policy: IconSlotPolicyConfig,
    val stackedEnabled: Boolean,
    val ignoreSystemHide: Boolean,
    val hidePrivacy: Boolean,
    val leftMode: Int
)

/** Shared icon controls and slot mapping, with no storage or host dependency. */
object IconTunerContract {
    const val KEY_POSITION = "icon_tuner_position"
    const val KEY_POSITION_VALUES = "icon_tuner_position_val"
    const val KEY_POSITION_REORDER = "icon_tuner_position_reorder"
    const val KEY_LEFT_MODE = "icon_left_container_mode"
    const val LEFT_MODE_DISABLED = 0
    const val LEFT_MODE_HOME = 1
    const val LEFT_MODE_HOME_AND_LOCKSCREEN = 2

    internal val leftPreferenceSlots = linkedMapOf(
        IconSettingsKeys.KEY_ICON_LEFT_ZEN to "icon_tuner_left_zen",
        IconSettingsKeys.KEY_ICON_LEFT_VOLUME to "icon_tuner_left_volume",
        IconSettingsKeys.KEY_ICON_LEFT_HOTSPOT to "icon_tuner_left_hotspot",
        IconSettingsKeys.KEY_ICON_LEFT_ALARM_CLOCK to "icon_tuner_left_alarm_clock",
        IconSettingsKeys.KEY_ICON_LEFT_LOCATION to "icon_tuner_left_location",
        IconSettingsKeys.KEY_ICON_LEFT_BLUETOOTH to "icon_tuner_left_bluetooth",
        IconSettingsKeys.KEY_ICON_LEFT_NFC to "icon_tuner_left_nfc",
        IconSettingsKeys.KEY_ICON_LEFT_VPN to "icon_tuner_left_vpn",
        IconSettingsKeys.KEY_ICON_LEFT_AIRPLANE to "icon_tuner_left_airplane",
        IconSettingsKeys.KEY_ICON_LEFT_HEADSET to "icon_tuner_left_headset"
    )

    /**
     * The left-placement toggles, in display order. The settings page renders exactly this list, so
     * a toggle added here cannot be silently missing from the UI (and vice versa: the page has no
     * label for an unknown key, which its test asserts).
     */
    val leftPreferenceKeys: List<String> = leftPreferenceSlots.keys.toList()

    /** Expands one UI toggle to the exact host slot names consumed by the left-container hook. */
    internal fun slotsForLeftPreference(key: String): List<String> = when (key) {
        IconSettingsKeys.KEY_ICON_LEFT_VOLUME -> listOf("volume", "mute")
        IconSettingsKeys.KEY_ICON_LEFT_ALARM_CLOCK -> listOf("alarm_clock")
        IconSettingsKeys.KEY_ICON_LEFT_LOCATION -> listOf("location", "gps")
        IconSettingsKeys.KEY_ICON_LEFT_HEADSET -> listOf("headset", "wireless_headset")
        else -> listOf(key.substringAfterLast('_'))
    }

}
