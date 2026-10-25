package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.*
import org.junit.Test

class MobileSignalSessionTest {
    @Test fun earlyFlowReplayKeepsTheLatestValuePerFieldUntilSubscriptionsArrive() {
        val session = MobileSignalSession()
        assertFalse(session.reduce(MobileSignalEvent.SignalModel(10, MobileSignalModel.cellular(1, 5))))
        assertFalse(session.reduce(MobileSignalEvent.SignalModel(10, MobileSignalModel.cellular(4, 5))))
        session.reduce(MobileSignalEvent.InService(10, false))
        session.reduce(MobileSignalEvent.DataConnected(10, true))
        assertTrue(session.state.subscriptions.isEmpty())
        session.subscriptions(listOf(10))
        val sim = requireNotNull(session.state.subscriptions[10])
        assertEquals(4, sim.level); assertEquals(MobileSignalKind.NO_SERVICE, sim.kind)
        assertEquals(true, sim.dataConnected)
    }
    @Test fun removedSubscriptionDoesNotReplayStaleValuesWhenItsIdReturns() {
        val session = MobileSignalSession()
        session.reduce(MobileSignalEvent.Roaming(1, true))
        session.reduce(MobileSignalEvent.Roaming(2, true))
        session.subscriptions(listOf(1))
        assertEquals(true, session.state.subscriptions[1]?.roaming)
        session.subscriptions(listOf(2))
        assertNull(session.state.subscriptions[2]?.roaming)
        session.clear(); session.subscriptions(listOf(1))
        assertNull(session.state.subscriptions[1]?.roaming)
    }
    @Test fun completenessRequiresFactoryAndCurrentGenerationBindingsForEveryRow() {
        val session = MobileSignalSession(); session.subscriptions(listOf(2, 1))
        assertFalse(session.complete(false, setOf(1, 2)) { true })
        assertFalse(session.complete(true, setOf(1)) { true })
        assertFalse(session.complete(true, setOf(1, 2)) { it == 1 })
        assertTrue(session.complete(true, setOf(1, 2)) { true })
        session.clear()
        assertFalse(session.complete(true, setOf(1, 2)) { true })
    }
    @Test fun adapterOnlyEventsReduceImmediatelyAndReplayPreservesNativeVisibility() {
        val session = MobileSignalSession()
        assertTrue(session.reduce(MobileSignalEvent.AirplaneMode(true)))
        assertTrue(session.state.airplaneMode)
        session.reduce(MobileSignalEvent.OriginalVisibility(7, false, true))
        session.subscriptions(listOf(7))
        assertFalse(requireNotNull(session.state.subscriptions[7]).originalVisible)
        assertTrue(requireNotNull(session.state.subscriptions[7]).originalDetailVisible)
    }
}
