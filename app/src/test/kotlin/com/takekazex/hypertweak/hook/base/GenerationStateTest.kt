package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test

class GenerationStateTest {
    private class Owner(private val fail: Boolean = false) : StaticHooker() {
        var target: Any? by generationState { null }
        var ready by generationState { false }
        val cache = generationResource(mutableMapOf<String, Any>()) { it.clear() }
        var restoredTarget: Any? = null
        override fun onPrepareHotReload() {
            restoredTarget = target
            if (fail) error("host restoration failed")
        }
    }

    @Test fun `retirement restores hosts before releasing values and retains cache identity`() {
        val owner = Owner()
        val target = Any()
        owner.target = target
        owner.ready = true
        val cache = owner.cache
        cache["host"] = target
        owner.prepareForHotReload()
        assertSame(target, owner.restoredTarget)
        assertNull(owner.target)
        assertFalse(owner.ready)
        assertSame(cache, owner.cache)
        assertTrue(cache.isEmpty())
        owner.target = Any()
        owner.ready = true
        owner.prepareForHotReload()
        assertNull(owner.target)
        assertFalse(owner.ready)
    }

    @Test fun `failed host restoration still releases every managed state`() {
        val owner = Owner(fail = true)
        owner.target = Any()
        owner.ready = true
        owner.cache["host"] = Any()
        assertThrows(IllegalStateException::class.java) { owner.prepareForHotReload() }
        assertNull(owner.target)
        assertFalse(owner.ready)
        assertTrue(owner.cache.isEmpty())
    }

    @Test fun `failed resource cleanup does not strand later resources or state`() {
        val owner = GenerationStateOwner()
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        owner.resource(Unit) { throw first }
        var cache by owner.state { "empty" }
        cache = "old host"
        owner.resource(Unit) { throw second }
        val cleared = owner.resource(mutableListOf(1)) { it.clear() }
        assertSame(first, assertThrows(IllegalStateException::class.java) { owner.reset() })
        assertArrayEquals(arrayOf(second), first.suppressed)
        assertEquals("empty", cache)
        assertTrue(cleared.isEmpty())
    }
}
