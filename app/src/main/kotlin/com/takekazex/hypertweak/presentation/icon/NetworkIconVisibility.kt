package com.takekazex.hypertweak.presentation.icon

import com.takekazex.hypertweak.settings.contract.IconSlotPolicyConfig
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconSurface

/** What the mobile-signal owner applies to the native cellular rows (shared by every surface). */
internal data class NativeMobileMask(
    /** Subscriptions whose whole native row is hidden (the first host visibility member). */
    val hidden: Set<Int> = emptySet(),
    /** Subscriptions whose native row keeps only its standalone type label. */
    val typeOnly: Set<Int> = emptySet()
)

/**
 * Display policy for the network glyphs, independent of connection and replacement readiness.
 * Native rows, the stacked replacement, Duo, the carrier rows and the settings preview all read
 * SIM and on-Wi-Fi hiding from here.
 */
internal data class NetworkIconVisibility(
    val hideMobileOnWifi: Boolean = false,
    val hideWifi: Boolean = false,
    val hideNonDefaultSim: Boolean = false,
    val hideSimOne: Boolean = false,
    val hideSimTwo: Boolean = false,
    val slotModes: Map<String, Int> = emptyMap()
) {
    private val slotPolicy = IconSlotPolicyConfig(slotModes = slotModes)

    /** SIM and on-Wi-Fi rules only; per-surface slot blocking is the icon managers' job. */
    fun hiddenSimIds(state: MobileSignalState, wifiDefault: Boolean, slotOf: (Int) -> Int): Set<Int> =
        if (hideMobileOnWifi && wifiDefault) state.subscriptionOrder.toSet()
        else state.hiddenSimIds(hideNonDefaultSim, hideSimOne, hideSimTwo, slotOf)

    fun hiddenCellularIds(
        state: MobileSignalState,
        surface: IconSurface,
        wifiDefault: Boolean,
        slotOf: (Int) -> Int
    ): Set<Int> {
        if (blocks(IconSlotPolicy.SLOT_MOBILE, surface)) return state.subscriptionOrder.toSet()
        return hiddenSimIds(state, wifiDefault, slotOf)
    }

    /**
     * Native row mask. Without a published stacked replacement only the SIM rules apply. With one,
     * the stacked bars stand in for every row, and the active data row stays as a type-only row
     * that is shown exactly when the host would show its standalone label
     * (`mobileTypeSingleVisible && isVisible.second`, as `MiuiMobileIconBinder` combines them).
     */
    fun nativeMask(
        state: MobileSignalState,
        slotOf: (Int) -> Int,
        stackedPublished: Boolean,
        ignoreSystemHide: Boolean,
        wifiDefault: Boolean = state.wifiConnected
    ): NativeMobileMask {
        val sims = hiddenSimIds(state, wifiDefault, slotOf)
        if (!stackedPublished) return NativeMobileMask(hidden = sims)
        val replaced = state.replacementMask(true, ignoreSystemHide)
        val typeHost = state.activeDataSubId.takeIf { it in replaced && it !in sims }
        val typeShown = typeHost?.let(state.subscriptions::get)
            ?.let { it.typeSingleVisible == true && it.originalDetailVisible } == true
        val shown = if (typeShown) setOfNotNull(typeHost) else emptySet()
        val hidden = replaced + sims - shown
        return NativeMobileMask(hidden = hidden, typeOnly = setOfNotNull(typeHost))
    }

    fun hidesWifi(surface: IconSurface, connected: Boolean): Boolean =
        hideWifi && connected || blocks(IconSlotPolicy.SLOT_WIFI, surface)

    private fun blocks(slot: String, surface: IconSurface): Boolean =
        IconSlotPolicy.blocksSlot(slot, surface, slotPolicy)
}
