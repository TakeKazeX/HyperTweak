package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureBarGestureDetectorTest {
    private fun detector() = GestureBarGestureDetector(
        moveSlop = 10f,
        doubleTapSlop = 24f,
        doubleTapTimeoutMs = 300L
    )

    @Test
    fun stationaryTouchTriggersLongPress() {
        val detector = detector()

        assertFalse(detector.onDown(1_000L, 100f, 200f))
        assertTrue(detector.onMove(105f, 204f, 1))
        assertTrue(detector.onLongPressTimeout())
        assertFalse(detector.onLongPressTimeout())
    }

    @Test
    fun movementCancelsLongPress() {
        val detector = detector()

        detector.onDown(1_000L, 100f, 200f)
        assertFalse(detector.onMove(120f, 200f, 1))
        assertFalse(detector.onLongPressTimeout())
    }

    @Test
    fun nearbyTapsWithinTimeoutTriggerDoubleTap() {
        val detector = detector()

        assertFalse(detector.onDown(1_000L, 100f, 200f))
        assertFalse(detector.onUp(1_060L, 102f, 201f))
        assertTrue(detector.onDown(1_250L, 106f, 204f))
        assertTrue(detector.onUp(1_310L, 107f, 205f))
    }

    @Test
    fun lateSecondTapStartsNewSequence() {
        val detector = detector()

        detector.onDown(1_000L, 100f, 200f)
        detector.onUp(1_050L, 100f, 200f)
        assertFalse(detector.onDown(1_400L, 100f, 200f))
        assertFalse(detector.onUp(1_450L, 100f, 200f))
    }

    @Test
    fun secondPointerCancelsCandidate() {
        val detector = detector()

        detector.onDown(1_000L, 100f, 200f)
        assertFalse(detector.onMove(100f, 200f, 2))
        assertFalse(detector.onLongPressTimeout())
    }
}
