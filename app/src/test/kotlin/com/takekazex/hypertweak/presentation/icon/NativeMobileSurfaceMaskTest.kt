package com.takekazex.hypertweak.presentation.icon

import com.takekazex.hypertweak.settings.contract.*
import org.junit.Assert.*
import org.junit.Test

class NativeMobileSurfaceMaskTest {
    private val state = MobileSignalState()
        .reduce(MobileSignalEvent.Subscriptions(listOf(11, 22)))
        .reduce(MobileSignalEvent.ActiveDataSubId(22))
        .reduce(MobileSignalEvent.SignalModel(11, MobileSignalModel.cellular(3, 5)))
        .reduce(MobileSignalEvent.SignalModel(22, MobileSignalModel.cellular(4, 5)))
        .reduce(MobileSignalEvent.TypeSingleVisible(22, true))

    private fun masks(mode: IconSlotMode, ready: Boolean, mobile: MobileSignalState = state,
        stackedMode: IconSlotMode = IconSlotMode.SHOW_EVERYWHERE): Map<PlanSurface, NativeMobileMask> {
        val policy = IconSlotPolicyConfig(slotModes = mapOf("mobile" to mode.value, "stacked_mobile_icon" to stackedMode.value))
        val network = NetworkDisplayModel(mobile, visibility = NetworkIconVisibility(slotModes = policy.slotModes))
        val claims = PlanSurface.entries.map { Claim(RendererId.STACKED, it,
            setOf(SlotPart.MobileBars(11), SlotPart.MobileBars(22)), ready, 100) }
        val plan = IconLayoutPlan.plan(policy, SignalStyle.STACKED, network, claims)
        return PlanSurface.entries.associateWith { network.nativeMask(plan[it]) }
    }

    @Test fun eachSlotRangeMasksItsOwnRowsAndKeepsTheNativeStandaloneType() {
        for (mode in IconSlotMode.entries) for ((surface, mask) in masks(mode, true)) {
            val cc = surface == PlanSurface.CONTROL_CENTER_COLLAPSED || surface == PlanSurface.CONTROL_CENTER_EXPANDED
            val blocked = mode == IconSlotMode.HIDE_EVERYWHERE ||
                mode == IconSlotMode.STATUS_BAR_ONLY && cc || mode == IconSlotMode.CONTROL_CENTER_ONLY && !cc
            assertEquals("$mode/$surface", if (blocked) NativeMobileMask(hidden = setOf(11, 22))
                else NativeMobileMask(hidden = setOf(11), typeOnly = setOf(22)), mask)
        }
    }

    @Test fun hiddenStackedHolderCannotClaimNativeBarsOnThatSurface() {
        for (mode in IconSlotMode.entries) for ((surface, mask) in masks(
            IconSlotMode.SHOW_EVERYWHERE, true, stackedMode = mode
        )) {
            val cc = surface == PlanSurface.CONTROL_CENTER_COLLAPSED || surface == PlanSurface.CONTROL_CENTER_EXPANDED
            val blocked = mode == IconSlotMode.HIDE_EVERYWHERE ||
                mode == IconSlotMode.STATUS_BAR_ONLY && cc || mode == IconSlotMode.CONTROL_CENTER_ONLY && !cc
            assertEquals("$mode/$surface", if (blocked) NativeMobileMask()
                else NativeMobileMask(hidden = setOf(11), typeOnly = setOf(22)), mask)
        }
    }

    @Test fun unavailableReplacementFailsOpenWhileUserHidingStillApplies() {
        assertTrue(masks(IconSlotMode.SHOW_EVERYWHERE, false).values.all { it == NativeMobileMask() })
        val ccOnly = masks(IconSlotMode.CONTROL_CENTER_ONLY, false)
        assertEquals(NativeMobileMask(hidden = setOf(11, 22)), ccOnly[PlanSurface.STATUS_BAR])
        assertEquals(NativeMobileMask(), ccOnly[PlanSurface.CONTROL_CENTER_EXPANDED])
        val unsupported = state.reduce(MobileSignalEvent.SignalModel(11, MobileSignalModel.satellite()))
        assertTrue(masks(IconSlotMode.SHOW_EVERYWHERE, true, unsupported).values.all { it == NativeMobileMask() })
        val noType = state.reduce(MobileSignalEvent.TypeSingleVisible(22, false))
        assertTrue(masks(IconSlotMode.SHOW_EVERYWHERE, true, noType).values.all { it.hidden == setOf(11, 22) })
    }
}
