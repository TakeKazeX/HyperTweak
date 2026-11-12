package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.presentation.icon.MobileSignalEvent
import com.takekazex.hypertweak.presentation.icon.MobileSignalKind
import com.takekazex.hypertweak.presentation.icon.MobileSignalModel
import com.takekazex.hypertweak.presentation.icon.MobileSignalState
import com.takekazex.hypertweak.presentation.icon.NativeMobileMask
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility
import com.takekazex.hypertweak.settings.contract.IconSlotMode
import com.takekazex.hypertweak.settings.contract.IconSurface
import com.takekazex.hypertweak.presentation.icon.MobileSubscriptionState

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileSignalStateTest {
    @Test
    fun nativeResourceCanChangeWithoutChangingSignalLevel() {
        val model = MobileSignalModel.cellular(3, 5)
        val initial = MobileSignalState().reduce(MobileSignalEvent.Subscriptions(listOf(11)))
            .reduce(MobileSignalEvent.SignalModel(11, model, signalIconId = 1001))
        val updated = initial.reduce(MobileSignalEvent.SignalModel(11, model, signalIconId = 1002))

        assertEquals(initial.subscriptions[11]?.renderLevel, updated.subscriptions[11]?.renderLevel)
        assertEquals(1002, updated.subscriptions[11]?.signalIconId)
    }

    @Test
    fun networkIconScopesAndRouteHidingAreIndependent() {
        val state = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(11, 22)))
            .reduce(MobileSignalEvent.ActiveDataSubId(11))
        val slotOf = mapOf(11 to 0, 22 to 1)::getValue
        val policy = NetworkIconVisibility(
            hideMobileOnWifi = true, hideWifi = true, hideSimOne = true,
            slotModes = mapOf("mobile" to IconSlotMode.STATUS_BAR_ONLY.value)
        )

        assertEquals(setOf(11), policy.hiddenCellularIds(state, IconSurface.STATUS_BAR, false, slotOf))
        assertEquals(setOf(11, 22), policy.hiddenCellularIds(state, IconSurface.STATUS_BAR, true, slotOf))
        assertEquals(setOf(11, 22), policy.hiddenCellularIds(state, IconSurface.CONTROL_CENTER, false, slotOf))
        assertFalse(policy.hidesWifi(IconSurface.STATUS_BAR, connected = false))
        assertTrue(policy.hidesWifi(IconSurface.STATUS_BAR, connected = true))
    }

    @Test
    fun settledStateSurvivesAHostRebindOfTheSameSims() {
        val shown = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(11, 22)))
            .reduce(MobileSignalEvent.SignalModel(11, MobileSignalModel.cellular(3, 5)))
        val rebinding = MobileSignalState().reduce(MobileSignalEvent.Subscriptions(listOf(22, 11)))

        assertEquals(shown, MobileSignalState.settle(shown, rebinding, ready = false))
        assertEquals(rebinding, MobileSignalState.settle(shown, rebinding, ready = true))
        // A different SIM set is not the same rebind; nothing partial is shown.
        val other = MobileSignalState().reduce(MobileSignalEvent.Subscriptions(listOf(33)))
        assertEquals(MobileSignalState(), MobileSignalState.settle(shown, other, ready = false))
        // Airplane mode is complete without its subscription rows.
        val airplane = MobileSignalState().reduce(MobileSignalEvent.AirplaneMode(true))
        assertEquals(airplane, MobileSignalState.settle(shown, airplane, ready = false))
    }

    @Test
    fun stackedMaskKeepsOnlyTheDataRowTypeLabel() {
        val slotOf = mapOf(11 to 0, 22 to 1)::getValue
        val dual = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(11, 22)))
            .reduce(MobileSignalEvent.ActiveDataSubId(22))
            .reduce(MobileSignalEvent.SignalModel(11, MobileSignalModel.cellular(3, 5)))
            .reduce(MobileSignalEvent.SignalModel(22, MobileSignalModel.cellular(4, 5)))
            .reduce(MobileSignalEvent.TypeSingleVisible(22, true))
        val policy = NetworkIconVisibility()

        // Without published bars only the SIM rules mask native rows.
        assertEquals(NativeMobileMask(), policy.nativeMask(dual, slotOf, stackedPublished = false, ignoreSystemHide = false))
        // The data row stays as a type-only row while the host shows its standalone label.
        assertEquals(NativeMobileMask(hidden = setOf(11), typeOnly = setOf(22)),
            policy.nativeMask(dual, slotOf, stackedPublished = true, ignoreSystemHide = false))
        // When the host hides the label, the type-only row is hidden with the rest.
        val noLabel = dual.reduce(MobileSignalEvent.TypeSingleVisible(22, false))
        assertEquals(NativeMobileMask(hidden = setOf(11, 22), typeOnly = setOf(22)),
            policy.nativeMask(noLabel, slotOf, stackedPublished = true, ignoreSystemHide = false))
        // A hidden data SIM keeps neither its bars nor its label.
        assertEquals(NativeMobileMask(hidden = setOf(11, 22)),
            NetworkIconVisibility(hideSimTwo = true).nativeMask(dual, slotOf, true, false))
        // Native SIM hiding is the same policy without the stacked bars.
        assertEquals(NativeMobileMask(hidden = setOf(11)),
            NetworkIconVisibility(hideNonDefaultSim = true).nativeMask(dual, slotOf, false, false))
        val onWifi = dual.reduce(MobileSignalEvent.WifiAvailable(11, true))
        assertEquals(NativeMobileMask(hidden = setOf(11, 22)),
            NetworkIconVisibility(hideMobileOnWifi = true).nativeMask(onWifi, slotOf, false, false))
    }

    @Test
    fun wifiDataSubscriptionDoesNotDependOnTheBitmapLabel() {
        val state = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(11)))
            .reduce(MobileSignalEvent.ActiveDataSubId(11))
            .reduce(MobileSignalEvent.SignalModel(11, MobileSignalModel.cellular(4, 5)))
            .reduce(MobileSignalEvent.InService(11, true))
            .reduce(MobileSignalEvent.NetworkType(11, "Custom 5G label"))

        assertEquals(11, state.wifiDataSubscription(true)?.subId)
        assertEquals(null, state.wifiCellularType(true))
        assertEquals(null, state.wifiDataSubscription(false))
        assertEquals(null, state.reduce(MobileSignalEvent.InService(11, false)).wifiDataSubscription(true))
    }

    @Test
    fun dataSwitchChangesActiveIdButNeverSwapsSubscriptionRows() {
        val first = MobileSignalModel.cellular(level = 4, numberOfLevels = 5)
        val second = MobileSignalModel.cellular(level = 2, numberOfLevels = 5)
        var state = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(101, 202)))
            .reduce(MobileSignalEvent.SignalModel(101, first))
            .reduce(MobileSignalEvent.SignalModel(202, second))
            .reduce(MobileSignalEvent.InService(101, true))
            .reduce(MobileSignalEvent.InService(202, true))
            .reduce(MobileSignalEvent.OriginalVisibility(101, true, true))
            .reduce(MobileSignalEvent.OriginalVisibility(202, true, true))
            .reduce(MobileSignalEvent.ActiveDataSubId(101))

        assertEquals(listOf(101, 202), state.rows.map { it.subId })
        assertEquals(listOf(4, 2), state.rows.map { it.normalizedLevel })

        state = state.reduce(MobileSignalEvent.ActiveDataSubId(202))

        assertEquals(202, state.activeDataSubId)
        assertEquals(listOf(101, 202), state.rows.map { it.subId })
        assertEquals(listOf(4, 2), state.rows.map { it.normalizedLevel })
    }

    @Test
    fun unknownActiveDataDoesNotReuseAnOldSubscription() {
        val state = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(11, 22)))
            .reduce(MobileSignalEvent.SignalModel(11, MobileSignalModel.cellular(3, 5)))
            .reduce(MobileSignalEvent.SignalModel(22, MobileSignalModel.cellular(3, 5)))
            .reduce(MobileSignalEvent.InService(11, true))
            .reduce(MobileSignalEvent.InService(22, true))
            .reduce(MobileSignalEvent.ActiveDataSubId(999))

        assertEquals(999, state.activeDataSubId)
        assertEquals(listOf(11, 22), state.rows.map { it.subId })
        assertTrue(state.canRenderReplacement())
        assertTrue(state.nonDefaultMask(true).isEmpty())
    }

    @Test
    fun airplaneModeDisablesReplacementWithoutChangingModelState() {
        val ready = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(11)))
            .reduce(MobileSignalEvent.SignalModel(11, MobileSignalModel.cellular(4, 5)))
            .reduce(MobileSignalEvent.InService(11, true))
            .reduce(MobileSignalEvent.ActiveDataSubId(11))
        assertTrue(ready.canRenderReplacement())

        val airborne = ready.reduce(MobileSignalEvent.AirplaneMode(true))

        assertFalse(airborne.canRenderReplacement())
        assertEquals(MobileSignalKind.CELLULAR, airborne.rows.single().kind)
        assertTrue(airborne.replacementMask(true).isEmpty())
    }

    @Test
    fun emptyNoServiceAndSatelliteRemainDifferentStates() {
        assertEquals(MobileSignalKind.EMPTY, MobileSignalState().kind)

        val state = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(1, 2, 3)))
            .reduce(MobileSignalEvent.SignalModel(1, MobileSignalModel.cellular(0, 5)))
            .reduce(MobileSignalEvent.InService(1, false))
            .reduce(MobileSignalEvent.SignalModel(2, MobileSignalModel.satellite(2, 5)))

        assertEquals(MobileSignalKind.NO_SERVICE, state.subscriptions.getValue(1).kind)
        assertEquals(MobileSignalKind.SATELLITE, state.subscriptions.getValue(2).kind)
        assertEquals(MobileSignalKind.UNKNOWN, state.subscriptions.getValue(3).kind)
        assertFalse(state.canRenderReplacement())
    }

    @Test
    fun removingAndReaddingSameSubIdStartsAsUnknown() {
        val withSignal = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(7)))
            .reduce(MobileSignalEvent.SignalModel(7, MobileSignalModel.cellular(4, 5)))
            .reduce(MobileSignalEvent.InService(7, true))
        val removed = withSignal.reduce(MobileSignalEvent.Subscriptions(emptyList()))
        val readded = removed.reduce(MobileSignalEvent.Subscriptions(listOf(7)))

        assertTrue(removed.subscriptions.isEmpty())
        assertEquals(MobileSignalKind.UNKNOWN, readded.subscriptions.getValue(7).kind)
        assertEquals(0, readded.subscriptions.getValue(7).normalizedLevel)
    }

    @Test
    fun originalVisibilityIsRequiredForReplacementButDetailIsPreserved() {
        val state = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(1)))
            .reduce(MobileSignalEvent.SignalModel(1, MobileSignalModel.cellular(4, 5)))
            .reduce(MobileSignalEvent.InService(1, true))
            .reduce(MobileSignalEvent.OriginalVisibility(1, false, true))

        assertFalse(state.canRenderReplacement())
        assertTrue(state.subscriptions.getValue(1).originalDetailVisible)
    }

    @Test
    fun nativeMasksOnlyAppearAfterSuccessfulPublish() {
        val state = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(1, 2)))
            .reduce(MobileSignalEvent.SignalModel(1, MobileSignalModel.cellular(4, 5)))
            .reduce(MobileSignalEvent.SignalModel(2, MobileSignalModel.cellular(4, 5)))
            .reduce(MobileSignalEvent.InService(1, true))
            .reduce(MobileSignalEvent.InService(2, true))
            .reduce(MobileSignalEvent.ActiveDataSubId(1))

        assertTrue(state.replacementMask(false).isEmpty())
        assertEquals(setOf(1, 2), state.replacementMask(true))
        assertEquals(setOf(2), state.nonDefaultMask(true))
    }

    @Test
    fun hidingTheDataSimDoesNotAlsoHideTheOtherPhysicalSlot() {
        val state = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(202, 101)))
            .reduce(MobileSignalEvent.ActiveDataSubId(101))
        val slots = mapOf(101 to 0, 202 to 1)

        assertEquals(setOf(101), state.hiddenSimIds(false, true, false, slots::getValue))
        assertEquals(setOf(202), state.hiddenSimIds(true, false, false, slots::getValue))
        assertEquals(setOf(101, 202), state.hiddenSimIds(true, true, false, slots::getValue))
    }

    @Test
    fun ignoreSystemHideBypassesOnlyOriginalVisibility() {
        val state = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(1, 2)))
            .reduce(MobileSignalEvent.SignalModel(1, MobileSignalModel.cellular(3, 5)))
            .reduce(MobileSignalEvent.SignalModel(2, MobileSignalModel.cellular(2, 5)))
            .reduce(MobileSignalEvent.OriginalVisibility(1, false, false))
            .reduce(MobileSignalEvent.OriginalVisibility(2, false, false))

        assertFalse(state.canRenderReplacement())
        assertTrue(state.canRenderReplacement(ignoreSystemHide = true))
        assertEquals(emptySet<Int>(), state.replacementMask(true))
        assertEquals(setOf(1, 2), state.replacementMask(true, ignoreSystemHide = true))
    }

    @Test
    fun wifiConnectedTracksAnySubscriptionDefaultWifi() {
        val disconnected = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(1, 2)))
            .reduce(MobileSignalEvent.WifiAvailable(1, false))
            .reduce(MobileSignalEvent.WifiAvailable(2, false))
        assertFalse(disconnected.wifiConnected)

        val oneRowConnected = disconnected.reduce(MobileSignalEvent.WifiAvailable(2, true))
        assertTrue(oneRowConnected.wifiConnected)

        val removed = oneRowConnected.reduce(MobileSignalEvent.Subscriptions(listOf(1)))
        assertFalse(removed.wifiConnected)
    }

    @Test
    fun levelsUseTheMaximumIndexAsDenominator() {
        assertEquals(4, MobileSubscriptionState.normalizeLevel(4, 5))
        assertEquals(4, MobileSubscriptionState.normalizeLevel(5, 6))
        assertEquals(2, MobileSubscriptionState.normalizeLevel(2, 5))
        assertEquals(4, MobileSubscriptionState.normalizeLevel(99, 0))
    }
}
