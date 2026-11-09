package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.settings.contract.IconTunerContract
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconTunerSnapshot

import com.takekazex.hypertweak.settings.contract.IconSlotPolicyConfig

import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSettingsPreferencePolicy
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility

/** Immutable preference snapshot shared by the P1 position and block-list hooks. */

/**
 * Reads icon settings once at a host-process setup point. Current HyperTweak keys win over the
 * XiaomiHelper names so an existing user choice of `false` is not mistaken for an absent value.
 */
object IconTunerOptions {
    fun snapshot(): IconTunerSnapshot {
        val stackedEnabled = Preferences.iconBoolean(IconSettingsKeys.KEY_ICON_STACKED_ENABLED)
        val leftMode = readLeftMode()
        val leftEnabled = leftMode != IconTunerContract.LEFT_MODE_DISABLED

        val leftSlots = LinkedHashSet<String>()
        if (leftEnabled) {
            IconTunerContract.leftPreferenceKeys.forEach { currentKey ->
                if (Preferences.iconBoolean(currentKey)) {
                    leftSlots += IconTunerContract.slotsForLeftPreference(currentKey)
                }
            }
        }

        val position = readInt(IconTunerContract.KEY_POSITION, null, IconSlotPolicyConfig.POSITION_SYSTEM)
        val customEntries = readStringSet(IconTunerContract.KEY_POSITION_VALUES, null)
        val slotModes = IconSettingsPreferencePolicy.slotModes(
            read = { slot -> explicitInt(Preferences.slotKey(slot)) },
            extraHidden = IconSettingsPreferencePolicy.extraHiddenSlots { key ->
                if (Preferences.contains(key)) Preferences.getString(key, "") else null
            }
        )
        val rotationLockEnabled = Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_ROTATION_LOCK_ICON, false)
        val rotationSlot = IconSlotPolicy.SLOT_ROTATION_LOCK
        if (!rotationLockEnabled || !IconSlotPolicy.rotationLockShowsOnStatusBar(
                IconSlotPolicy.modeFor(rotationSlot, slotModes), rotationSlot in leftSlots
            )
        ) leftSlots.remove(rotationSlot)
        val policy = IconSlotPolicyConfig(
            position = position,
            customOrderEntries = customEntries,
            reorderHidden = Preferences.getBoolean(IconTunerContract.KEY_POSITION_REORDER, false),
            slotModes = slotModes,
            enabledModuleSlots = buildSet {
                if (stackedEnabled) addAll(IconSlotPolicy.STACKED_SLOTS)
                if (rotationLockEnabled) add(rotationSlot)
            },
            leftSlots = leftSlots
        )
        return IconTunerSnapshot(
            policy = policy,
            stackedEnabled = stackedEnabled,
            ignoreSystemHide = Preferences.iconBoolean(IconSettingsKeys.KEY_ICON_IGNORE_SYS_HIDE),
            hidePrivacy = Preferences.iconBoolean(IconSettingsKeys.KEY_ICON_HIDE_PRIVACY),
            leftMode = leftMode
        )
    }

    internal fun networkVisibility(): NetworkIconVisibility = NetworkIconVisibility(
        hideMobileOnWifi = Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI, false),
        hideWifi = Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_WIFI_UNAVAILABLE, false),
        hideType = Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_TYPE, false),
        hideNonDefaultSim = Preferences.hideNonDefaultSim(),
        hideSimOne = Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_SIM_ONE, false),
        hideSimTwo = Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_SIM_TWO, false),
        slotModes = snapshot().policy.slotModes
    )

    private fun readLeftMode(): Int = IconSettingsPreferencePolicy.leftMode(
        explicitInt(IconTunerContract.KEY_LEFT_MODE),
        Preferences.iconBoolean(IconSettingsKeys.KEY_ICON_LEFT_CONTAINER_ENABLED)
    )

    private fun explicitInt(key: String): Int? =
        if (Preferences.contains(key)) Preferences.getInt(key, 0) else null

    private fun readInt(primary: String, legacy: String?, default: Int): Int {
        val key = sequenceOf(primary, legacy)
            .filterNotNull()
            .firstOrNull(Preferences::contains)
            ?: return default
        return Preferences.getInt(key, default)
    }

    private fun readStringSet(primary: String, legacy: String?): Set<String> {
        val key = sequenceOf(primary, legacy)
            .filterNotNull()
            .firstOrNull(Preferences::contains)
            ?: return emptySet()
        return Preferences.getStringSet(key, emptySet())
    }

}
