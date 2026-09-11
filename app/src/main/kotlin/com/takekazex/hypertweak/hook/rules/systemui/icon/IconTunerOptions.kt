package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences

/** Immutable preference snapshot shared by the P1 position and block-list hooks. */
data class IconTunerSnapshot(
    val policy: IconSlotPolicyConfig,
    val stackedEnabled: Boolean,
    val ignoreSystemHide: Boolean,
    val hidePrivacy: Boolean,
    val leftMode: Int
)

/**
 * Reads icon settings once at a host-process setup point. Current HyperTweak keys win over the
 * XiaomiHelper names so an existing user choice of `false` is not mistaken for an absent value.
 */
object IconTunerOptions {
    const val KEY_POSITION = "icon_tuner_position"
    const val KEY_POSITION_VALUES = "icon_tuner_position_val"
    const val KEY_POSITION_REORDER = "icon_tuner_position_reorder"
    const val KEY_LEFT_MODE = "icon_left_container_mode"
    const val LEFT_MODE_DISABLED = 0
    const val LEFT_MODE_HOME = 1
    const val LEFT_MODE_HOME_AND_LOCKSCREEN = 2
    private const val LEGACY_IGNORE_SYSTEM_HIDE = "statusbar_ignore_sys_hide"
    private const val LEGACY_HIDE_PRIVACY = "icon_tuner_hide_privacy"
    private const val LEGACY_EXTRA_BLOCKED = "icon_tuner_ext_blocked"
    private const val LEGACY_STACKED_ENABLED = "icon_tuner_stacked_enabled"
    private const val LEGACY_LEFT_CONTAINER = "icon_tuner_left_container"

    private val policySlots = listOf(
        "mobile", "no_sim", "airplane", "wifi", "demo_wifi", "hotspot", "vpn",
        "network_speed", "bluetooth", "bluetooth_handsfree_battery", "handle_battery", "nfc",
        "gps", "location", "wireless_headset", "phone", "pad", "pc", "sound_box_group",
        "stereo", "sound_box_screen", "sound_box", "tv", "glasses", "car", "camera",
        "dist_compute", "headset", "alarm_clock", "zen", "volume", "second_space",
        "compound_icon"
    ) + IconSlotPolicy.MODULE_SLOTS

    private val leftPreferenceSlots = linkedMapOf(
        Preferences.KEY_ICON_LEFT_ZEN to "icon_tuner_left_zen",
        Preferences.KEY_ICON_LEFT_VOLUME to "icon_tuner_left_volume",
        Preferences.KEY_ICON_LEFT_HOTSPOT to "icon_tuner_left_hotspot",
        Preferences.KEY_ICON_LEFT_ALARM_CLOCK to "icon_tuner_left_alarm_clock",
        Preferences.KEY_ICON_LEFT_LOCATION to "icon_tuner_left_location",
        Preferences.KEY_ICON_LEFT_BLUETOOTH to "icon_tuner_left_bluetooth",
        Preferences.KEY_ICON_LEFT_NFC to "icon_tuner_left_nfc",
        Preferences.KEY_ICON_LEFT_VPN to "icon_tuner_left_vpn",
        Preferences.KEY_ICON_LEFT_AIRPLANE to "icon_tuner_left_airplane",
        Preferences.KEY_ICON_LEFT_HEADSET to "icon_tuner_left_headset",
        Preferences.KEY_ICON_LEFT_COMPOUND to "icon_tuner_left_compound_icon"
    )

    fun snapshot(): IconTunerSnapshot {
        val modes = LinkedHashMap<String, Int>()
        policySlots.forEach { slot ->
            readSlotMode(slot)?.let { modes[slot] = it }
        }

        val stackedEnabled = readBoolean(
            Preferences.KEY_ICON_STACKED_ENABLED,
            LEGACY_STACKED_ENABLED,
            false
        )
        val leftMode = readLeftMode()
        val leftEnabled = leftMode != LEFT_MODE_DISABLED

        val leftSlots = LinkedHashSet<String>()
        if (leftEnabled) {
            leftPreferenceSlots.forEach { (currentKey, legacyKey) ->
                if (readBoolean(currentKey, legacyKey, false)) {
                    leftSlots += slotsForLeftPreference(currentKey)
                }
            }
        }

        val position = readInt(KEY_POSITION, null, IconSlotPolicyConfig.POSITION_SYSTEM)
        val customEntries = readStringSet(KEY_POSITION_VALUES, null)
        val extraHidden = parseSlotList(
            readString(Preferences.KEY_ICON_EXT_BLOCKED, LEGACY_EXTRA_BLOCKED, "")
        )
        val policy = IconSlotPolicyConfig(
            position = position,
            customOrderEntries = customEntries,
            reorderHidden = readBoolean(KEY_POSITION_REORDER, null, false),
            slotModes = modes,
            extraHiddenSlots = extraHidden,
            enabledModuleSlots = buildSet {
                if (stackedEnabled) addAll(IconSlotPolicy.SIGNAL_SLOTS)
            },
            leftSlots = leftSlots
        )
        return IconTunerSnapshot(
            policy = policy,
            stackedEnabled = stackedEnabled,
            ignoreSystemHide = readBoolean(
                Preferences.KEY_ICON_IGNORE_SYS_HIDE,
                LEGACY_IGNORE_SYSTEM_HIDE,
                false
            ),
            hidePrivacy = readBoolean(
                Preferences.KEY_ICON_HIDE_PRIVACY,
                LEGACY_HIDE_PRIVACY,
                false
            ),
            leftMode = leftMode
        )
    }

    /** Expands one UI toggle to the exact host slot names consumed by the left-container hook. */
    internal fun slotsForLeftPreference(key: String): List<String> = when (key) {
        Preferences.KEY_ICON_LEFT_VOLUME -> listOf("volume", "mute")
        Preferences.KEY_ICON_LEFT_ALARM_CLOCK -> listOf("alarm_clock")
        Preferences.KEY_ICON_LEFT_LOCATION -> listOf("location", "gps")
        Preferences.KEY_ICON_LEFT_HEADSET -> listOf("headset", "wireless_headset")
        Preferences.KEY_ICON_LEFT_COMPOUND -> listOf(
            "compound_location",
            "compound_alarm_clock",
            "compound_zen",
            "compound_volume_vibrate",
            "compound_volume_mute"
        )
        else -> listOf(key.substringAfterLast('_'))
    }

    /** New left-placement modes preserve the old Boolean without reading it as an Int. */
    private fun readLeftMode(): Int {
        if (Preferences.contains(KEY_LEFT_MODE)) {
            return Preferences.getInt(KEY_LEFT_MODE, LEFT_MODE_DISABLED)
                .coerceIn(LEFT_MODE_DISABLED, LEFT_MODE_HOME_AND_LOCKSCREEN)
        }
        val legacyKey = when {
            Preferences.contains(Preferences.KEY_ICON_LEFT_CONTAINER_ENABLED) ->
                Preferences.KEY_ICON_LEFT_CONTAINER_ENABLED
            Preferences.contains(LEGACY_LEFT_CONTAINER) -> LEGACY_LEFT_CONTAINER
            else -> return LEFT_MODE_DISABLED
        }
        return if (Preferences.getBoolean(legacyKey, false)) LEFT_MODE_HOME else LEFT_MODE_DISABLED
    }

    private fun readSlotMode(slot: String): Int? {
        val key = IconSlotPolicy.preferenceSlotAliases(slot)
            .map(Preferences::slotKey)
            .firstOrNull(Preferences::contains)
            ?: return null
        return Preferences.getInt(key, 0)
    }

    private fun readBoolean(primary: String, legacy: String?, default: Boolean): Boolean {
        val key = sequenceOf(primary, legacy)
            .filterNotNull()
            .firstOrNull(Preferences::contains)
            ?: return default
        return Preferences.getBoolean(key, default)
    }

    private fun readInt(primary: String, legacy: String?, default: Int): Int {
        val key = sequenceOf(primary, legacy)
            .filterNotNull()
            .firstOrNull(Preferences::contains)
            ?: return default
        return Preferences.getInt(key, default)
    }

    private fun readString(primary: String, legacy: String?, default: String): String {
        val key = sequenceOf(primary, legacy)
            .filterNotNull()
            .firstOrNull(Preferences::contains)
            ?: return default
        return Preferences.getString(key, default)
    }

    private fun readStringSet(primary: String, legacy: String?): Set<String> {
        val key = sequenceOf(primary, legacy)
            .filterNotNull()
            .firstOrNull(Preferences::contains)
            ?: return emptySet()
        return Preferences.getStringSet(key, emptySet())
    }

    private fun parseSlotList(value: String): Set<String> = value
        .split(',', ' ', '\uFF0C')
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toCollection(LinkedHashSet())
}
