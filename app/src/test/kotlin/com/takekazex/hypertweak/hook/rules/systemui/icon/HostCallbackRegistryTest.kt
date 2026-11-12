package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Test

class HostCallbackRegistryTest {
    @Test fun dispatchOrdersByPriorityAndKeyAndIsolatesExceptions() {
        val events = mutableListOf<String>()
        val failures = mutableListOf<String?>()
        val registry = HostCallbackRegistry<Int> { failures += it.message }
        registry.subscribe(1, "z") { events += "z:$it" }
        registry.subscribe(0, "b") { events += "b:$it"; error("failure") }
        registry.subscribe(0, "a") { events += "a:$it" }
        registry.dispatch(7)
        assertEquals(listOf("a:7", "b:7", "z:7"), events)
        assertEquals(listOf("failure"), failures)
    }

    @Test fun cancellationDuringDispatchAndClearPreventFutureDelivery() {
        val events = mutableListOf<String>()
        val registry = HostCallbackRegistry<Int> { throw AssertionError(it) }
        lateinit var later: AutoCloseable
        registry.subscribe(0, "first") { events += "first:$it"; later.close() }
        later = registry.subscribe(1, "later") { events += "later:$it" }
        registry.dispatch(1)
        registry.dispatch(2)
        registry.clear()
        registry.dispatch(3)
        assertEquals(listOf("first:1", "first:2"), events)
    }
}
