package com.takekazex.hypertweak.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsHapticTimingTest {
    @Test fun `paces the queue at one motor slot per crossing`() {
        assertEquals(50L, RecentsHapticTiming.delayUntilNext(100L, 120L))
        assertEquals(1L, RecentsHapticTiming.delayUntilNext(100L, 169L))
        assertEquals(0L, RecentsHapticTiming.delayUntilNext(100L, 170L))
        assertEquals(0L, RecentsHapticTiming.delayUntilNext(100L, 500L))
        assertEquals(0L, RecentsHapticTiming.delayUntilNext(0L, 500L))
        assertEquals(70L, RecentsHapticTiming.delayUntilNext(0L, 0L))
    }

    @Test fun `the slot outlasts every measured predefined effect`() {
        // Slowest measured drain on the target was TICK at 67 ms.
        assertTrue(RecentsHapticTiming.MIN_INTERVAL_MS > 67L)
    }

    @Test fun `backlog keeps the newest crossings and stays bounded`() {
        assertEquals(0, RecentsHapticTiming.overflow(0))
        assertEquals(0, RecentsHapticTiming.overflow(RecentsHapticTiming.MAX_PENDING - 1))
        assertEquals(1, RecentsHapticTiming.overflow(RecentsHapticTiming.MAX_PENDING))
        assertEquals(7, RecentsHapticTiming.overflow(10))
    }
}
