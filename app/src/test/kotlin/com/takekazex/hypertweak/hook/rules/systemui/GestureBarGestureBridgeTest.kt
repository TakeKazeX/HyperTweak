package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureBarGestureBridgeTest {
    @Test
    fun recognizesBeforeHyperOsShortLongPressTimeout() {
        assertEquals(225L, GestureBarLongPressTiming.recognitionTimeout(300L))
    }

    @Test
    fun preservesLeadForDefaultAndroidTimeout() {
        assertEquals(425L, GestureBarLongPressTiming.recognitionTimeout(500L))
    }

    @Test
    fun neverExtendsAnAlreadyShortSystemTimeout() {
        assertEquals(150L, GestureBarLongPressTiming.recognitionTimeout(150L))
    }

    @Test
    fun decodesOnlyKnownBridgeGestures() {
        assertEquals(
            GestureBarGesture.LONG_PRESS,
            GestureBarGesture.fromPersistedId(GestureBarGesture.LONG_PRESS.persistedId)
        )
        assertEquals(
            GestureBarGesture.DOUBLE_TAP,
            GestureBarGesture.fromPersistedId(GestureBarGesture.DOUBLE_TAP.persistedId)
        )
        assertNull(GestureBarGesture.fromPersistedId(-1))
    }

    @Test
    fun defersOnlyRequestsAssociatedWithTheCurrentDown() {
        val gate = GestureBarPilferGate<String>(associationWindowMs = 125L)

        assertTrue(gate.beginCandidate(1_000L).isEmpty())
        assertTrue(gate.tryDefer("launcher-token", 1_008L))
        assertEquals(listOf("launcher-token"), gate.releaseCandidate())

        gate.beginCandidate(2_000L)
        assertFalse(gate.tryDefer("late-token", 2_126L))
        assertTrue(gate.releaseCandidate().isEmpty())
    }

    @Test
    fun keepsSubsequentRequestsDeferredUntilOwnershipIsResolved() {
        val gate = GestureBarPilferGate<String>(associationWindowMs = 125L)

        gate.beginCandidate(1_000L)
        assertTrue(gate.tryDefer("first-token", 1_010L))
        assertTrue(gate.tryDefer("second-token", 1_500L))

        assertEquals(
            listOf("first-token", "second-token"),
            gate.releaseCandidate()
        )
        assertNull(gate.candidateStartedAtOrNull())
    }

    @Test
    fun consumingRecognizedGestureDropsLauncherOwnershipRequest() {
        val gate = GestureBarPilferGate<String>()

        gate.beginCandidate(1_000L)
        assertTrue(gate.tryDefer("launcher-token", 1_005L))
        assertTrue(gate.hasDeferredRequests())

        gate.consumeCandidate()

        assertFalse(gate.hasDeferredRequests())
        assertTrue(gate.releaseCandidate().isEmpty())
    }

    @Test
    fun calculatesPilferFailOpenDeadlineAfterRecognitionWindow() {
        assertEquals(
            1_675L,
            GestureBarPilferTiming.failOpenDeadline(
                downEventTime = 1_000L,
                longPressTimeoutMs = 425L
            )
        )
    }

    @Test
    fun matchesOnlyTheLauncherSwipeUpMonitor() {
        assertTrue(GestureBarMonitorNames.isLauncherSwipeUp("[Gesture Monitor] swipe-up"))
        assertFalse(GestureBarMonitorNames.isLauncherSwipeUp("[Gesture Monitor] GestureStubLeft"))
        assertFalse(GestureBarMonitorNames.isLauncherSwipeUp("swipe-up"))
    }

    @Test
    fun matchesOnlyHyperTweakSystemUiMonitors() {
        assertTrue(
            GestureBarMonitorNames.isHyperTweakGestureBar(
                "[Gesture Monitor] HyperTweakGestureBar-0"
            )
        )
        assertFalse(
            GestureBarMonitorNames.isHyperTweakGestureBar("[Gesture Monitor] back-gesture")
        )
    }
}
