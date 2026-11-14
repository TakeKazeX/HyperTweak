package com.takekazex.hypertweak.presentation.icon

import com.takekazex.hypertweak.settings.contract.*
import org.junit.Assert.*
import org.junit.Test

class DuoExpandedSignalTest {
    private val mobile = MobileSignalState()
        .reduce(MobileSignalEvent.Subscriptions(listOf(11, 22)))
        .reduce(MobileSignalEvent.ActiveDataSubId(22))
        .reduce(MobileSignalEvent.SignalModel(11, MobileSignalModel.cellular(3, 5)))
        .reduce(MobileSignalEvent.SignalModel(22, MobileSignalModel.cellular(4, 5)))
        .reduce(MobileSignalEvent.TypeSingleVisible(22, true))
    private val network = NetworkDisplayModel(mobile, slotOf = { if (it == 11) 0 else 1 })
    private val claims = PlanSurface.entries.map { surface ->
        Claim(RendererId.STACKED, surface, setOf(SlotPart.MobileBars(11), SlotPart.MobileBars(22)), true, 100)
    }
    private val slot = SlotPart.Slot(IconSlotPolicy.SLOT_STACKED_SIGNAL)
    private fun config(vararg extra: Pair<String, Any>): IconConfig = IconConfigDecoder.decode(
        (mapOf<String, Any>(IconSettingsKeys.KEY_ICON_SIGNAL_STYLE to 2,
            IconSettingsKeys.KEY_ICON_DUO_EXPANDED_SIGNAL_STYLE to 1) + extra)::get
    )

    @Test fun onlyExpandedRowsYieldToTheReadyStackedHolderForEitherBatteryChoice() {
        for (batteryStyle in 0..1) {
            val config = config(IconSettingsKeys.KEY_ICON_DUO_EXPANDED to batteryStyle)
            val plan = IconLayoutPlan.plan(config, network, claims)
            for (surface in PlanSurface.entries) {
                val expanded = surface == PlanSurface.CONTROL_CENTER_EXPANDED
                assertEquals("$surface/$batteryStyle", expanded, config.stackedSignalOn(surface))
                assertEquals(if (expanded) Ownership.Replaced(RendererId.STACKED) else Ownership.Native,
                    plan[surface].owner(SlotPart.MobileBars(11)))
                assertEquals(if (expanded) NativeMobileMask(hidden = setOf(11), typeOnly = setOf(22))
                    else NativeMobileMask(), network.nativeMask(plan[surface]))
                assertEquals(!expanded, plan[surface].owner(slot) is Ownership.Hidden)
                assertEquals(Ownership.Native, plan[surface].owner(SlotPart.Battery))
            }
        }
    }

    @Test fun loadingFailureOrNativeSelectionReleasesNativeRows() {
        val config = config()
        val unready = IconLayoutPlan.plan(config, network, claims.map { it.copy(ready = false) })
        assertTrue(unready.surfaces.values.all { network.nativeMask(it) == NativeMobileMask() })
        val native = config(IconSettingsKeys.KEY_ICON_DUO_EXPANDED_SIGNAL_STYLE to 0)
        val plan = IconLayoutPlan.plan(native, network, claims)
        assertTrue(plan.surfaces.values.all { network.nativeMask(it) == NativeMobileMask() })
        assertFalse(native.hasStackedSignal)
        assertFalse(IconConfigDecoder.decode { null }.hasStackedSignal)
        assertFalse(config(IconSettingsKeys.KEY_ICON_DUO_EXPANDED_SIGNAL_STYLE to 9).duoExpandedStacked)
        assertFalse(config(IconSettingsKeys.KEY_ICON_SIGNAL_STYLE to 0).hasStackedSignal)
    }

    @Test fun userHidingAndCarrierClaimsStillWin() {
        val hidden = config(IconSettingsPreferencePolicy.slotKey(IconSlotPolicy.SLOT_STACKED_SIGNAL) to 4)
        val plan = IconLayoutPlan.plan(hidden, network, claims)
        assertTrue(plan.surfaces.values.all { network.nativeMask(it) == NativeMobileMask() })
        val surface = PlanSurface.CONTROL_CENTER_EXPANDED
        val carrier = Claim(RendererId.CARRIER, surface, setOf(SlotPart.MobileRow(11), SlotPart.MobileRow(22)), true, 200)
        val carrierPlan = IconLayoutPlan.plan(config(), network, claims + carrier)
        assertEquals(Ownership.Replaced(RendererId.CARRIER), carrierPlan[surface].owner(SlotPart.MobileBars(11)))
        assertEquals(NativeMobileMask(), network.nativeMask(carrierPlan[surface]))
    }

    @Test fun statusBarOnlySimHidingRestoresTheFullNativeExpandedDestination() {
        val config = config(IconSettingsKeys.KEY_ICON_SIM_ONE_VISIBILITY to SimVisibilityPolicy.STATUS_BAR_ONLY)
        val plan = IconLayoutPlan.plan(config, network, claims)
        val expanded = plan[PlanSurface.CONTROL_CENTER_EXPANDED]
        assertTrue(expanded.owner(slot) is Ownership.Hidden)
        assertEquals(NativeMobileMask(), network.nativeMask(expanded))
    }
}
