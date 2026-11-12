package com.takekazex.hypertweak.presentation.icon

import com.takekazex.hypertweak.settings.contract.*
import com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierBlockConfig
import com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierBlockPolicy
import org.junit.Assert.*
import org.junit.Test

class SimVisibilityTest {
    private val state = MobileSignalState()
        .reduce(MobileSignalEvent.Subscriptions(listOf(22, 11)))
        .reduce(MobileSignalEvent.ActiveDataSubId(22))
        .reduce(MobileSignalEvent.SignalModel(11, MobileSignalModel.cellular(3, 5)))
        .reduce(MobileSignalEvent.SignalModel(22, MobileSignalModel.cellular(4, 5)))
        .reduce(MobileSignalEvent.TypeSingleVisible(22, true))
    private val slotOf = mapOf(11 to 0, 22 to 1)::getValue

    @Test fun threeModesSeparateStatusBarFromControlCenter() {
        for (mode in 0..2) {
            val policy = NetworkIconVisibility(simOneMode = mode)
            assertEquals(if (mode == 0) emptySet<Int>() else setOf(11),
                policy.hiddenCellularIds(state, IconSurface.STATUS_BAR, false, slotOf))
            assertEquals(if (mode == 2) setOf(11) else emptySet<Int>(),
                policy.hiddenCellularIds(state, IconSurface.CONTROL_CENTER, false, slotOf))
            assertEquals(mode == 2, policy.hidesCarrier(0, state, slotOf))
            assertFalse(policy.hidesCarrier(1, state, slotOf))
        }
    }

    @Test fun nonDefaultFollowsDataSwitchAndStrongestOverlappingRuleWins() {
        val policy = NetworkIconVisibility(simOneMode = 1, nonDefaultSimMode = 2)
        assertTrue(policy.hidesCarrier(0, state, slotOf))
        val switched = state.reduce(MobileSignalEvent.ActiveDataSubId(11))
        assertFalse(policy.hidesCarrier(0, switched, slotOf))
        assertTrue(policy.hidesCarrier(1, switched, slotOf))
        assertEquals(setOf(11, 22), policy.hiddenCellularIds(switched, IconSurface.STATUS_BAR, false, slotOf))
        assertEquals(setOf(22), policy.hiddenCellularIds(switched, IconSurface.CONTROL_CENTER, false, slotOf))
        assertFalse(NetworkIconVisibility(nonDefaultSimMode = 2).hidesCarrier(0,
            state.reduce(MobileSignalEvent.Subscriptions(listOf(11))), slotOf))
    }

    @Test fun stackedStatusOnlyKeepsNativeControlCenterRowsAvailable() {
        val config = IconConfigDecoder.decode(mapOf<String, Any>(
            IconSettingsKeys.KEY_ICON_SIGNAL_STYLE to SignalStyle.STACKED.ordinal,
            IconSettingsKeys.KEY_ICON_SIM_ONE_VISIBILITY to 1)::get)
        val network = NetworkDisplayModel(state, slotOf = slotOf,
            visibility = NetworkIconVisibility(simOneMode = 1))
        val claims = PlanSurface.entries.map { Claim(RendererId.STACKED, it,
            setOf(SlotPart.MobileBars(11), SlotPart.MobileBars(22)), true, 100) }
        val plan = IconLayoutPlan.plan(config, network, claims)
        val expanded = plan[PlanSurface.CONTROL_CENTER_EXPANDED]
        assertEquals(NativeMobileMask(), network.nativeMask(expanded))
        assertEquals(Ownership.Hidden("user"), expanded.owner(SlotPart.Slot(IconSlotPolicy.SLOT_STACKED_SIGNAL)))
        val mirror = plan[PlanSurface.CONTROL_CENTER_COLLAPSED]
        assertEquals(setOf(11), network.nativeMask(mirror).hidden)
        assertEquals(Ownership.Hidden("user"), mirror.owner(SlotPart.MobileBars(11)))
        assertEquals(Ownership.Replaced(RendererId.STACKED), mirror.owner(SlotPart.MobileBars(22)))
        assertEquals(Ownership.Native, mirror.owner(SlotPart.Slot(IconSlotPolicy.SLOT_STACKED_SIGNAL)))
        assertEquals(setOf(11), network.nativeMask(plan[PlanSurface.STATUS_BAR]).hidden)
    }

    @Test fun animationMirrorNeverRestoresSimsThatExistOnlyAtTheExpandedDestination() {
        for (style in SignalStyle.entries) for (active in listOf(11, 22)) {
            val current = state.reduce(MobileSignalEvent.ActiveDataSubId(active))
            val visibility = NetworkIconVisibility(nonDefaultSimMode = 1)
            val network = NetworkDisplayModel(current, slotOf = slotOf, visibility = visibility)
            val config = IconConfigDecoder.decode(mapOf<String, Any>(
                IconSettingsKeys.KEY_ICON_SIGNAL_STYLE to style.ordinal,
                IconSettingsKeys.KEY_ICON_NON_DEFAULT_SIM_VISIBILITY to 1)::get)
            val hidden = setOf(if (active == 11) 22 else 11)
            val plan = IconLayoutPlan.plan(config, network, emptyList())
            assertEquals(hidden, network.nativeMask(plan[PlanSurface.STATUS_BAR]).hidden)
            assertEquals(hidden, network.nativeMask(plan[PlanSurface.CONTROL_CENTER_COLLAPSED]).hidden)
            assertEquals(emptySet<Int>(), network.nativeMask(plan[PlanSurface.CONTROL_CENTER_EXPANDED]).hidden)
        }
    }

    @Test fun allHidingRemovesCompactCarrierRowsIncludingWhenBothAreHidden() {
        for (hidden in listOf(setOf(0), setOf(1), setOf(0, 1))) {
            val rows = CarrierBlockPolicy.resolve(state, 4,
                CarrierBlockConfig(hiddenCarrierSlots = hidden, dataFirst = true), true, slotOf)
            assertTrue(rows.filter { it.slot in hidden }.none { it.visible || it.hasWifi || it.hasSignal || it.hasType })
            assertEquals(2 - hidden.size, rows.count { it.visible })
        }
    }

    @Test fun migrationAndExplicitDefaultsAreSharedByUiAndRuntime() {
        val old = mapOf<String, Any>(IconSettingsKeys.KEY_ICON_HIDE_SIM_ONE to true,
            IconSettingsKeys.KEY_ICON_HIDE_CARRIER_TWO to true, IconSettingsKeys.KEY_ICON_HIDE_SIM_AUTO to true,
            IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_ONE to true)
        val migrated = IconConfigDecoder.decode(old::get)
        assertEquals(1, migrated.network.simOneVisibility)
        assertEquals(2, migrated.network.simTwoVisibility)
        assertEquals(1, migrated.network.nonDefaultSimVisibility)
        val reset = IconConfigDecoder.decode((old + mapOf(
            IconSettingsKeys.KEY_ICON_SIM_ONE_VISIBILITY to 0,
            IconSettingsKeys.KEY_ICON_SIM_TWO_VISIBILITY to 0,
            IconSettingsKeys.KEY_ICON_NON_DEFAULT_SIM_VISIBILITY to 0))::get)
        assertFalse(reset.network.hideSimOne || reset.network.hideSimTwo || reset.network.hideNonDefaultSim)
        assertTrue(reset.controlCenter.hideLsCarrierOne)
        assertTrue(IconConfigReload.domains(migrated, reset)!!.containsAll(setOf(
            IndependentIconDomain.POLICY, IndependentIconDomain.MOBILE_SIGNAL,
            IndependentIconDomain.CONTROL_CENTER_HEADER, IndependentIconDomain.DUO)))
    }
}
