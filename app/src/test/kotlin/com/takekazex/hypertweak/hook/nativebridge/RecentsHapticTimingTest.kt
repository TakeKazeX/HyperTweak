package com.takekazex.hypertweak.hook.nativebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsHapticTimingTest {
    private fun ms(value: Long) = value * 1_000_000L

    @Test fun `fast crossings never accumulate delayed playback`() {
        val gate = RecentsHapticTiming()
        assertTrue(gate.admit(ms(100), ms(100)))
        for (time in 101L..169L) assertFalse(gate.admit(ms(time), ms(time)))
        assertTrue(gate.admit(ms(170), ms(170)))
        assertTrue(gate.admit(ms(1000), ms(1000)))
    }

    @Test fun `delayed broadcasts cannot replay an ended gesture`() {
        val gate = RecentsHapticTiming()
        assertFalse(gate.admit(ms(100), ms(151)))
        assertFalse(gate.admit(ms(100), ms(170)))
        assertTrue(gate.admit(ms(200), ms(220)))
        assertFalse(gate.admit(ms(210), ms(290)))
        assertTrue(gate.admit(ms(300), ms(300)))
    }

    @Test fun `out of order duplicate missing and future events are rejected`() {
        val gate = RecentsHapticTiming()
        assertFalse(gate.admit(0, ms(100)))
        assertFalse(gate.admit(ms(200), ms(100)))
        assertTrue(gate.admit(ms(100), ms(100)))
        assertFalse(gate.admit(ms(100), ms(200)))
        assertFalse(gate.admit(ms(90), ms(200)))
        assertTrue(gate.admit(ms(200), ms(200)))
    }

    @Test fun `disable reset and new publisher have no inherited backlog`() {
        val gate = RecentsHapticTiming()
        assertTrue(gate.admit(ms(100), ms(100)))
        gate.reset(ms(130))
        assertFalse(gate.admit(ms(120), ms(140))) // fresh transport event from before disable
        assertFalse(gate.admit(ms(100), ms(200)))
        assertTrue(gate.admit(ms(201), ms(201)))
        assertTrue(RecentsHapticTiming().admit(ms(202), ms(202)))
    }
}
