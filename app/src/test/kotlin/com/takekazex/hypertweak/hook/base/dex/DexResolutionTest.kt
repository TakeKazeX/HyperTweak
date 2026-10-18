package com.takekazex.hypertweak.hook.base.dex

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DexResolutionTest {
    @Test fun `invalid cached reflection falls back to one scan and only persists valid results`() {
        val resolver = DexResolution(); var scans = 0; var invalid = 0
        var cached = mapOf("target" to "obsolete")
        val result = resolver.resolve("build", false, { cached }, { scans++; mapOf("target" to "valid") },
            { values, _ -> check(values["target"] == "valid"); "resolved" }, { cached = it }, { invalid++ })
        assertEquals("resolved", result)
        assertEquals(1, scans)
        assertEquals(1, invalid)
        assertEquals("valid", cached["target"])
    }

    @Test fun `invalid fresh reflection never overwrites a previous cache`() {
        var writes = 0
        val result = DexResolution().resolve("build", false, { null }, { mapOf("bad" to "target") },
            { _, _ -> null as String? }, { writes++ }, { throw it })
        assertNull(result)
        assertEquals(0, writes)
    }

    @Test fun `concurrent consumers share published descriptors and cache-only startup never waits`() {
        val resolver = DexResolution()
        val pool = Executors.newFixedThreadPool(2)
        val scanning = CountDownLatch(1); val release = CountDownLatch(1)
        var cache: Map<String, String>? = null
        try {
            val first = pool.submit<String?> {
                resolver.resolve("same", false, { cache }, {
                    scanning.countDown(); check(release.await(3, TimeUnit.SECONDS)); mapOf("key" to "value")
                }, { values, _ -> values["key"] }, { cache = it }, { throw it })
            }
            assertTrue(scanning.await(3, TimeUnit.SECONDS))
            val cacheOnly = pool.submit<String?> {
                resolver.resolve("same", true, { fail("must not read during publication"); null },
                    { fail("must not scan"); null }, { _, _ -> "bad" }, {}, { throw it })
            }
            assertNull(cacheOnly.get(1, TimeUnit.SECONDS))
            val second = pool.submit<String?> {
                resolver.resolve("same", false, { cache }, { fail("second scan"); null },
                    { values, _ -> values["key"] }, {}, { throw it })
            }
            release.countDown()
            assertEquals("value", first.get(3, TimeUnit.SECONDS))
            assertEquals("value", second.get(3, TimeUnit.SECONDS))
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
