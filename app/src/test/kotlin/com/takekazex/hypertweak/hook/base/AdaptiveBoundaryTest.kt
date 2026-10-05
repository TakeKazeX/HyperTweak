package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Collections

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
    @Test fun `immutable framework arguments are copied and unrelated inputs retain identity`() {
        val keep = Any()
        val arguments = Collections.unmodifiableList(listOf(keep, false, 7, false))
        val types = arrayOf(Any::class.java, java.lang.Boolean.TYPE, Integer.TYPE, java.lang.Boolean.TYPE)
        val result = BooleanArgumentOverride.copy(types, arguments, intArrayOf(1, 3))!!
        assertEquals(listOf(keep, true, 7, true), result.toList())
        assertEquals(listOf(keep, false, 7, false), arguments)
        assertSame(keep, result[0])
    }
    @Test fun `changed constructor contracts fail closed as a whole`() {
        val types = arrayOf(java.lang.Boolean.TYPE, String::class.java)
        assertNull(BooleanArgumentOverride.copy(types, listOf(false), intArrayOf(0)))
        assertNull(BooleanArgumentOverride.copy(types, listOf(false, "native"), intArrayOf(0, 1)))
        assertNull(BooleanArgumentOverride.copy(types, listOf(false, "native"), intArrayOf(0, 0)))
        assertNull(BooleanArgumentOverride.copy(types, listOf(false, "native"), intArrayOf(2)))
        assertNull(BooleanArgumentOverride.copy(types, listOf(null, "native"), intArrayOf(0)))
    }
}
