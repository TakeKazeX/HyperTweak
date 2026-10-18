package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.session.SessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRegistryTest {
    @Test fun `one owner maps to one stable session`() {
        val registry = SessionRegistry()
        val owner = Any()
        val first = registry.getOrCreate(owner)
        val second = registry.getOrCreate(owner)
        assertSame(first, second)
        assertSame(first, registry[owner])
        assertEquals(listOf<Any>(owner), registry.owners())
        assertEquals(listOf(first), registry.snapshot())
    }

    @Test fun `removing an owner leaves other sessions intact`() {
        val registry = SessionRegistry()
        val firstOwner = Any()
        val secondOwner = Any()
        val first = registry.getOrCreate(firstOwner)
        val second = registry.getOrCreate(secondOwner)
        registry.remove(firstOwner)
        assertNull(registry[firstOwner])
        assertSame(second, registry[secondOwner])
        assertTrue(registry.snapshot().none { it.owner === firstOwner })
    }

    @Test fun `clear drops every session`() {
        val registry = SessionRegistry()
        registry.getOrCreate(Any())
        registry.getOrCreate(Any())
        registry.clear()
        assertTrue(registry.owners().isEmpty())
        assertTrue(registry.snapshot().isEmpty())
        assertNull(registry[null])
    }
}
