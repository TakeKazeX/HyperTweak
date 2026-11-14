package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSettingsPreferencePolicy
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconSlotPolicyConfig
import com.takekazex.hypertweak.settings.contract.IconTunerContract
import com.takekazex.hypertweak.settings.contract.IconTunerSnapshot
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

/** Immutable preference snapshot shared by the P1 position and block-list hooks. */

/**
 * Reads icon settings once at a host-process setup point. Current HyperTweak keys win over the
 * XiaomiHelper names so an existing user choice of `false` is not mistaken for an absent value.
 */
object IconTunerOptions {
    fun snapshot(): IconTunerSnapshot {
        val config = Preferences.iconConfig()
        val stackedEnabled = config.hasStackedSignal
        val leftMode = config.slots.int(IconTunerContract.KEY_LEFT_MODE).coerceIn(0, 2)
        val leftEnabled = leftMode != IconTunerContract.LEFT_MODE_DISABLED

        val leftSlots = LinkedHashSet<String>()
        if (leftEnabled) {
            IconTunerContract.leftPreferenceKeys.forEach { currentKey ->
                if (config.slots.boolean(currentKey)) {
                    leftSlots += IconTunerContract.slotsForLeftPreference(currentKey)
                }
            }
        }

        val position = config.slots.int(IconTunerContract.KEY_POSITION)
        val customEntries = (config.value(IconTunerContract.KEY_POSITION_VALUES) as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
        val slotModes = config.slots.slotModes
        val rotationLockEnabled = config.controlCenter.boolean(SystemUiSettingsKeys.KEY_CC_ROTATION_LOCK_ICON)
        val rotationSlot = IconSlotPolicy.SLOT_ROTATION_LOCK
        if (!rotationLockEnabled || !IconSlotPolicy.rotationLockShowsOnStatusBar(
                IconSlotPolicy.modeFor(rotationSlot, slotModes), rotationSlot in leftSlots
            )
        ) leftSlots.remove(rotationSlot)
        val policy = IconSlotPolicyConfig(
            position = position,
            customOrderEntries = customEntries,
            reorderHidden = config.slots.boolean(IconTunerContract.KEY_POSITION_REORDER),
            slotModes = slotModes,
            enabledModuleSlots = buildSet {
                if (stackedEnabled) addAll(IconSlotPolicy.STACKED_SLOTS)
                if (rotationLockEnabled) add(rotationSlot)
            },
            leftSlots = leftSlots,
            stackedTypeLeading = IconSettingsPreferencePolicy.stackedTypeLeading(config.cellular.cellularTypeOrder)
        )
        return IconTunerSnapshot(
            policy = policy,
            stackedEnabled = stackedEnabled,
            ignoreSystemHide = config.slots.boolean(IconSettingsKeys.KEY_ICON_IGNORE_SYS_HIDE),
            hidePrivacy = config.slots.boolean(IconSettingsKeys.KEY_ICON_HIDE_PRIVACY),
            leftMode = leftMode,
            leftKeyguardCarrier = config.slots.leftKeyguardCarrier
        )
    }

    internal fun networkVisibility(): NetworkIconVisibility = NetworkIconVisibility(
        hideMobileOnWifi = Preferences.iconConfig().network.hideMobileOnWifi,
        hideWifi = Preferences.iconConfig().wifi.hideWifiUnavailable,
        simOneMode = Preferences.iconConfig().network.simOneVisibility,
        simTwoMode = Preferences.iconConfig().network.simTwoVisibility,
        nonDefaultSimMode = Preferences.iconConfig().network.nonDefaultSimVisibility,
        slotModes = snapshot().policy.slotModes
    )

}
