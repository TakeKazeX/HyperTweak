package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakVmRegistryTest {
    private class State(val source: Any, val lease: VmLease = VmLease(), var received: Int = 0)

    @Test fun independentVmFlowsAndProducerSwitch() {
        val states = WeakVmRegistry<Any, State>()
        val one = Any(); val two = Any(); val other = Any()
        val sourceA = Any(); val sourceB = Any()
        val a = states.getOrPut(one) { State(sourceA) }
        val b = states.getOrPut(two) { State(sourceA) }
        val c = states.getOrPut(other) { State(sourceB) }
        assertNotSame(a, b)
        assertSame(a, states.getOrPut(one) { error("must retain surviving VM state") })
        fun emit(source: Any, value: Int) = states.snapshot().forEach { (_, state) ->
            if (state.source === source) state.received = value
        }
        emit(sourceA, 7)
        assertEquals(listOf(7, 7, 0), listOf(a.received, b.received, c.received))
        emit(sourceB, 3)
        assertEquals(listOf(7, 7, 3), listOf(a.received, b.received, c.received))
    }

    @Test fun retirementInvalidatesOnlyThatVmLease() {
        val first = VmLease(); val second = VmLease()
        val oldFirst = first.renew(); val oldSecond = second.renew()
        val recovered = first.renew()
        assertFalse(first.isCurrent(oldFirst))
        assertTrue(second.isCurrent(oldSecond))
        assertTrue(first.isCurrent(recovered))
    }
}
