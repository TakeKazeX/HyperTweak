package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.template.CameraSeatRegistry
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandCameraSeat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class IslandCameraSeatTest {
    @Test fun `overlapping leases keep the camera seat until the last release`() {
        val registry = CameraSeatRegistry()
        val parent = Any()
        assertFalse(registry.holds(parent))
        registry.acquire(parent)
        registry.acquire(parent)
        assertTrue(registry.holds(parent))
        registry.release(parent)
        assertTrue(registry.holds(parent))
        registry.release(parent)
        assertFalse(registry.holds(parent))
        registry.release(parent) // Idempotent: extra release must not underflow.
        assertFalse(registry.holds(parent))
    }

    @Test fun `independent area parents do not share ownership`() {
        val registry = CameraSeatRegistry()
        val first = Any()
        val second = Any()
        registry.acquire(first)
        assertTrue(registry.holds(first))
        assertFalse(registry.holds(second))
        registry.release(first)
        assertFalse(registry.holds(first))
    }

    @Test fun `two lease objects on one parent clear only after both release`() {
        val registry = CameraSeatRegistry()
        val parent = Any()
        registry.acquire(parent)
        registry.acquire(parent)
        registry.release(parent)
        assertTrue(registry.holds(parent))
        registry.release(parent)
        assertFalse(registry.holds(parent))
    }

    @Test fun `process registry exposes and clears tracked parents`() {
        IslandCameraSeat.clear()
        val parent = Any()
        IslandCameraSeat.acquire(parent)
        assertTrue(IslandCameraSeat.holds(parent))
        assertEquals(1, IslandCameraSeat.tracked())
        IslandCameraSeat.clear()
        assertFalse(IslandCameraSeat.holds(parent))
        assertEquals(0, IslandCameraSeat.tracked())
    }
}
