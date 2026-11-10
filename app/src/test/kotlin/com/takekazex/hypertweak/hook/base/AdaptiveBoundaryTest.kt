package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdaptiveBoundaryTest {
    @Test fun `system context without storage never calls SharedPreferences`() {
        var calls = 0
        assertNull(OptionalAppCache.open<String>({ null }) { calls++; "bad" }.getOrThrow())
        assertEquals(0, calls)
        assertNull(OptionalAppCache.open<String>({ error("No data directory found for package android") }) { calls++; "bad" }.getOrThrow())
        assertEquals(0, calls)
    }
    @Test fun `valid storage and failed cache creation have distinct outcomes`() {
        assertEquals("cache", OptionalAppCache.open({ File("/app") }) { "cache" }.getOrThrow())
        val error = IllegalStateException("credential storage locked")
        assertSame(error, OptionalAppCache.open<String>({ File("/app") }) { throw error }.exceptionOrNull())
    }
}
