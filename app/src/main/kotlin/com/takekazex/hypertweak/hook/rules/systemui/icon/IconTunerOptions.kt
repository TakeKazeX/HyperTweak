package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.settings.contract.IconTunerContract
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconTunerSnapshot

import com.takekazex.hypertweak.settings.contract.IconSlotPolicyConfig

import com.takekazex.hypertweak.settings.contract.IconSettingsKeys

import com.takekazex.hypertweak.hook.Preferences

/** Immutable preference snapshot shared by the P1 position and block-list hooks. */

/**
 * Reads icon settings once at a host-process setup point. Current HyperTweak keys win over the
 * XiaomiHelper names so an existing user choice of `false` is not mistaken for an absent value.
 */
object IconTunerOptions {
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
        "dist_compute", "headset", "alarm_clock", "zen", "volume", "second_space"
    ) + IconSlotPolicy.MODULE_SLOTS

    fun snapshot(): IconTunerSnapshot {
        val modes = LinkedHashMap<String, Int>()
        policySlots.forEach { slot ->
            readSlotMode(slot)?.let { modes[slot] = it }
        }

        val stackedEnabled = readBoolean(
            IconSettingsKeys.KEY_ICON_STACKED_ENABLED,
            LEGACY_STACKED_ENABLED,
            false
        )
        val leftMode = readLeftMode()
        val leftEnabled = leftMode != IconTunerContract.LEFT_MODE_DISABLED

        val leftSlots = LinkedHashSet<String>()
        if (leftEnabled) {
            IconTunerContract.leftPreferenceSlots.forEach { (currentKey, legacyKey) ->
                if (readBoolean(currentKey, legacyKey, false)) {
                    leftSlots += IconTunerContract.slotsForLeftPreference(currentKey)
                }
            }
        }

        val position = readInt(IconTunerContract.KEY_POSITION, null, IconSlotPolicyConfig.POSITION_SYSTEM)
        val customEntries = readStringSet(IconTunerContract.KEY_POSITION_VALUES, null)
        // The legacy extra-hidden list is not a separate mode: an entry means HIDE_EVERYWHERE, which
        // the per-slot dropdown also offers, so it is folded in as a *default* for that slot.
        val slotModes = IconSlotPolicy.foldExtraHiddenIntoModes(
            modes,
            parseSlotList(readString(IconSettingsKeys.KEY_ICON_EXT_BLOCKED, LEGACY_EXTRA_BLOCKED, ""))
        )
        val policy = IconSlotPolicyConfig(
            position = position,
            customOrderEntries = customEntries,
            reorderHidden = readBoolean(IconTunerContract.KEY_POSITION_REORDER, null, false),
            slotModes = slotModes,
            enabledModuleSlots = buildSet {
                if (stackedEnabled) addAll(IconSlotPolicy.SIGNAL_SLOTS)
            },
            leftSlots = leftSlots
        )
        return IconTunerSnapshot(
            policy = policy,
            stackedEnabled = stackedEnabled,
            ignoreSystemHide = readBoolean(
                IconSettingsKeys.KEY_ICON_IGNORE_SYS_HIDE,
                LEGACY_IGNORE_SYSTEM_HIDE,
                false
            ),
            hidePrivacy = readBoolean(
                IconSettingsKeys.KEY_ICON_HIDE_PRIVACY,
                LEGACY_HIDE_PRIVACY,
                false
            ),
            leftMode = leftMode
        )
    }

    /** New left-placement modes preserve the old Boolean without reading it as an Int. */
    private fun readLeftMode(): Int {
        if (Preferences.contains(IconTunerContract.KEY_LEFT_MODE)) {
            return Preferences.getInt(IconTunerContract.KEY_LEFT_MODE, IconTunerContract.LEFT_MODE_DISABLED)
                .coerceIn(IconTunerContract.LEFT_MODE_DISABLED, IconTunerContract.LEFT_MODE_HOME_AND_LOCKSCREEN)
        }
        val legacyKey = when {
            Preferences.contains(IconSettingsKeys.KEY_ICON_LEFT_CONTAINER_ENABLED) ->
                IconSettingsKeys.KEY_ICON_LEFT_CONTAINER_ENABLED
            Preferences.contains(LEGACY_LEFT_CONTAINER) -> LEGACY_LEFT_CONTAINER
            else -> return IconTunerContract.LEFT_MODE_DISABLED
        }
        return if (Preferences.getBoolean(legacyKey, false)) IconTunerContract.LEFT_MODE_HOME else IconTunerContract.LEFT_MODE_DISABLED
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

    private fun parseSlotList(value: String): Set<String> =
        LinkedHashSet(IconSlotPolicy.parseSlotList(value))
}
