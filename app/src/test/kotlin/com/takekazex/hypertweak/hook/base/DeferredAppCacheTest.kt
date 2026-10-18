package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeferredAppCacheTest {
    @Test fun `locked storage defers repeated readers and recovers on the next eligible read`() {
        var now = 0L
        var calls = 0
        val cache = DeferredAppCache<String>({ now })
        assertNull(cache.open { calls++; Result.success(null) }.getOrThrow())
        repeat(1_000) { assertNull(cache.open { calls++; Result.success("too early") }.getOrThrow()) }
        assertEquals(1, calls)
        now = 5_000
        assertEquals("ready", cache.open { calls++; Result.success("ready") }.getOrThrow())
        assertEquals("ready", cache.open { error("must reuse the published cache") }.getOrThrow())
        assertEquals(2, calls)
    }

    @Test fun `failure reporting cannot reopen the same failed cache`() {
        val cache = DeferredAppCache<String>({ 0 })
        val failure = IllegalStateException("credential storage unavailable")
        val result = cache.open { Result.failure(failure) }.onFailure {
            assertNull(cache.open { error("recursive logging reopened storage") }.getOrThrow())
        }
        assertSame(failure, result.exceptionOrNull())
    }

    @Test fun `concurrent and recursive readers do not wait on the initializer`() {
        val cache = DeferredAppCache<String>({ 0 })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<String?> {
                cache.open {
                    assertNull(cache.open { error("recursive initialization") }.getOrThrow())
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    Result.success("ready")
                }.getOrThrow()
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertNull(cache.open { error("concurrent initialization") }.getOrThrow())
            release.countDown()
            assertEquals("ready", result.get(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }
}
