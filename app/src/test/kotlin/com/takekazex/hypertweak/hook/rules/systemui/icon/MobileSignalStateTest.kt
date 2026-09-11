package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileSignalStateTest {
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
    fun levelsUseTheMaximumIndexAsDenominator() {
        assertEquals(4, MobileSubscriptionState.normalizeLevel(4, 5))
        assertEquals(4, MobileSubscriptionState.normalizeLevel(5, 6))
        assertEquals(2, MobileSubscriptionState.normalizeLevel(2, 5))
        assertEquals(4, MobileSubscriptionState.normalizeLevel(99, 0))
    }
}
