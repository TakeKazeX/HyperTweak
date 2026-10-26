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
    @Test fun `retirement waits on the same monitor as concurrent writers`() {
        val owner = GenerationStateOwner()
        val cache = owner.resource(hashMapOf("remainder" to 1)) {
            assertTrue(Thread.holdsLock(it))
            it.clear()
        }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val started = java.util.concurrent.CountDownLatch(1)
        try {
            val reset = synchronized(cache) {
                val task = pool.submit { started.countDown(); owner.reset() }
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertThrows(java.util.concurrent.TimeoutException::class.java) {
                    task.get(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                assertEquals(1, cache["remainder"])
                task
            }
            reset.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(cache.isEmpty())
        } finally { pool.shutdownNow() }
    }

    @Test fun `affinity dispatch happens before locking and cleanup finishes before reset returns`() {
        val owner = GenerationStateOwner()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val caller = Thread.currentThread()
        var resetThread: Thread? = null
        val cache = hashMapOf("view" to 1)
        owner.resource(cache, dispatch = { action ->
            assertFalse(Thread.holdsLock(cache))
            pool.submit { action() }.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }) {
            resetThread = Thread.currentThread()
            assertTrue(Thread.holdsLock(it))
            it.clear()
        }
        try {
            owner.reset()
            assertNotSame(caller, resetThread)
            assertNotNull(resetThread)
            assertTrue(cache.isEmpty())
        } finally { pool.shutdownNow() }
    }

    @Test fun `resource and terminal fallbacks run when ordered feature cleanup fails`() {
        var retired = false
        var closed = false
        val owner = object : StaticHooker() {
            init {
                generationCleanup { retired = true }
                generationCleanup { closed = true }
            }
            override fun onPrepareHotReload() { error("restoration failed") }
        }
        assertThrows(IllegalStateException::class.java) { owner.prepareForHotReload() }
        assertTrue(retired)
        assertTrue(closed)
    }

    @Test fun `required installation metadata cannot leak into a replacement generation`() {
        val state = GenerationStateOwner()
        var target: String by state.requiredState()
        assertThrows(UninitializedPropertyAccessException::class.java) { target }
        target = "old-loader contract"
        assertEquals("old-loader contract", target)
        state.reset()
        assertThrows(UninitializedPropertyAccessException::class.java) { target }
        target = "new-loader contract"
        assertEquals("new-loader contract", target)
    }

}
