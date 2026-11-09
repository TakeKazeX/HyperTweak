package com.takekazex.hypertweak.presentation.icon

import com.takekazex.hypertweak.settings.contract.IconSlotPolicyConfig
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconSurface

/** Display policy for the network glyphs, independent of connection and replacement readiness. */
internal data class NetworkIconVisibility(
    val hideMobileOnWifi: Boolean = false,
    val hideWifi: Boolean = false,
    val hideNonDefaultSim: Boolean = false,
    val hideSimOne: Boolean = false,
    val hideSimTwo: Boolean = false,
    val slotModes: Map<String, Int> = emptyMap()
) {
    private val slotPolicy = IconSlotPolicyConfig(slotModes = slotModes)

    fun hiddenCellularIds(
        state: MobileSignalState,
        surface: IconSurface,
        wifiDefault: Boolean,
        slotOf: (Int) -> Int
    ): Set<Int> {
        if (blocks(IconSlotPolicy.SLOT_MOBILE, surface) || hideMobileOnWifi && wifiDefault) {
            return state.subscriptionOrder.toSet()
        }
        return state.hiddenSimIds(hideNonDefaultSim, hideSimOne, hideSimTwo, slotOf)
    }

    fun hidesWifi(surface: IconSurface, connected: Boolean): Boolean =
        hideWifi && connected || blocks(IconSlotPolicy.SLOT_WIFI, surface)

    private fun blocks(slot: String, surface: IconSurface): Boolean =
        IconSlotPolicy.blocksSlot(slot, surface, slotPolicy)
}
