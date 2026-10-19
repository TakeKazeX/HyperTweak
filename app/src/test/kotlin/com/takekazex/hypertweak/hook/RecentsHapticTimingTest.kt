package com.takekazex.hypertweak.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentsHapticTimingTest {
    @Test fun `coalesces bursts to the next free motor slot`() {
        assertEquals(35L, RecentsHapticTiming.delayUntilNext(100L, 120L))
        assertEquals(16L, RecentsHapticTiming.delayUntilNext(100L, 139L))
        assertEquals(0L, RecentsHapticTiming.delayUntilNext(100L, 155L))
        assertEquals(0L, RecentsHapticTiming.delayUntilNext(100L, 500L))
        assertEquals(0L, RecentsHapticTiming.delayUntilNext(0L, 500L))
        assertEquals(55L, RecentsHapticTiming.delayUntilNext(0L, 0L))
    }
}
