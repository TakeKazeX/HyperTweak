package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.*
import org.junit.Test

class FrameUpdateGateTest {
    @Test fun burstRendersLatestInputOnceAndNextFrameStillRuns() {
        val gate = FrameUpdateGate()
        val frames = mutableListOf<() -> Unit>()
        val rendered = mutableListOf<Int>()
        var input = 0
        fun update(value: Int) {
            input = value
            gate.request()?.let { ticket -> frames += { gate.drain(ticket) { rendered += input } } }
        }
        repeat(100) { update(it) }
        assertEquals(1, frames.size)
        frames.removeAt(0).invoke()
        assertEquals(listOf(99), rendered)
        update(200)
        frames.removeAt(0).invoke()
        assertEquals(listOf(99, 200), rendered)
    }

    @Test fun retiredCallbackCannotRenderOrConsumeNewOwnerFrame() {
        val gate = FrameUpdateGate()
        var renders = 0
        val old = gate.request()!!
        gate.cancel()
        val fresh = gate.request()!!
        gate.drain(old) { renders++ }
        assertEquals(0, renders)
        gate.drain(fresh) { renders++ }
        assertEquals(1, renders)
    }
}
