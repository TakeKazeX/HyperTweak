package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test

class HookSessionRegistryTest {
    @Test fun `components share one installation and only last unload retires it`() {
        val registry = HookSessionRegistry<String, Any>()
        val loader = Any(); val first = Any(); val second = Any(); val session = Any()
        assertEquals(session to true, registry.acquire(first, loader, "first") { session })
        assertEquals(session to false, registry.acquire(second, loader, "second") { error("duplicate hook installation") })
        assertEquals(1, registry.sessions().size)
        assertEquals(1, registry.bindings().size)
        assertNull(registry.release(first))
        assertEquals(listOf("second"), registry.states())
        assertEquals(second, registry.bindings().single().first)
        assertSame(session, registry[second])
        assertSame(session, registry.release(second))
        assertTrue(registry.sessions().isEmpty())
    }

    @Test fun `repeated callback does not add references and reloaded owner gets a fresh session`() {
        val registry = HookSessionRegistry<String, Any>()
        val owner = Any(); val loader = Any(); val session = Any()
        registry.acquire(owner, loader, "state") { session }
        registry.acquire(owner, loader, "state") { error("duplicate") }
        assertSame(session, registry.release(owner))
        val fresh = Any()
        assertEquals(fresh to true, registry.acquire(owner, loader, "new") { fresh })
    }

    @Test fun `loader and owner identity never rely on custom equality`() {
        data class Equal(val value: Int)
        val registry = HookSessionRegistry<Int, Any>()
        val first = Any(); val second = Any()
        registry.acquire(Equal(0), Equal(1), 1) { first }
        registry.acquire(Equal(0), Equal(1), 2) { second }
        assertEquals(2, registry.sessions().size)
        registry.clear()
        assertTrue(registry.states().isEmpty())
    }
}
