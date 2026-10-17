package com.takekazex.hypertweak.hook.rules.screenshot

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScreenshotFrameMaterialCacheTest {
    @Test fun `unchanged material decodes once and changed material retires the previous resource`() {
        val disposed = mutableListOf<Any>()
        val cache = ScreenshotFrameMaterialCache<Any> { disposed += it }
        val first = Any()
        var loads = 0
        repeat(3) { assertSame(first, cache.use("v1", { loads++; first }) { it }) }
        assertEquals(1, loads)
        assertNull(cache.use("v2", { null }) { it })
        assertEquals(listOf(first), disposed)
        val second = Any()
        assertSame(second, cache.use("v2", { second }) { it })
        cache.close()
        cache.close()
        assertEquals(listOf(first, second), disposed)
        assertNull(cache.use("v3", { fail("retired cache loaded a bitmap"); Any() }) { it })
    }

    @Test fun `retirement waits for the active native render before recycling its bitmap`() {
        val rendering = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val cache = ScreenshotFrameMaterialCache<Any> { disposed.countDown() }
        try {
            val render = pool.submit<Int> {
                cache.use("v1", { Any() }) {
                    rendering.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    assertEquals(1L, disposed.count)
                    42
                }
            }
            assertTrue(rendering.await(5, TimeUnit.SECONDS))
            val close = pool.submit { closing.countDown(); cache.close() }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertFalse(close.isDone)
            finish.countDown()
            assertEquals(42, render.get(5, TimeUnit.SECONDS))
            close.get(5, TimeUnit.SECONDS)
            assertEquals(0L, disposed.count)
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `failed native operation releases the monitor and leaves material usable`() {
        var recycled = false
        val resource = Any()
        val cache = ScreenshotFrameMaterialCache<Any> { recycled = true }
        assertThrows(IllegalStateException::class.java) {
            cache.use("v1", { resource }) { error("render failed") }
        }
        assertSame(resource, cache.use("v1", { fail("unexpected decode"); Any() }) { it })
        assertFalse(recycled)
        cache.close()
        assertTrue(recycled)
    }
}
