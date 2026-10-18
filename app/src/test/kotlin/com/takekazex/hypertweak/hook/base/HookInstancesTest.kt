package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test

class HookInstancesTest {
    @Test fun `equal hosts are tracked separately and clearing releases all observations`() {
        data class Host(val key: Int)
        val first = Host(1); val second = Host(1)
        val instances = HookInstances()
        instances.add(first); instances.add(first); instances.add(second); instances.add(null)
        val snapshot = instances.snapshot()
        assertEquals(2, snapshot.size)
        assertTrue(snapshot.any { it === first })
        assertTrue(snapshot.any { it === second })
        instances.clear()
        assertTrue(instances.snapshot().isEmpty())
    }
}
