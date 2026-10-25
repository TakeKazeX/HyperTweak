package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test

class ExpiringReadCacheTest {
    @Test fun expiryAndAcknowledgementInvalidationPreserveFalseAndZero() {
        var now = 10L
        val cache = ExpiringReadCache(100) { now }
        cache["boolean"] = false
        cache["integer"] = 0
        assertEquals(false, cache["boolean"])
        assertEquals(0, cache["integer"])
        now = 109
        assertEquals(0, cache["integer"])
        now = 110
        assertNull(cache["integer"])
        cache["integer"] = 3
        cache.invalidate("integer")
        assertNull(cache["integer"])
        cache["integer"] = 4
        cache.clear()
        assertNull(cache["integer"])
    }

    @Test fun expiredReaderCannotEvictAReplacementWrittenDuringItsClockRead() {
        var now = 0L
        var replaceOnRead = false
        lateinit var cache: ExpiringReadCache
        cache = ExpiringReadCache(10) {
            if (replaceOnRead) {
                replaceOnRead = false
                cache["key"] = "new"
            }
            now
        }
        cache["key"] = "old"
        now = 10
        replaceOnRead = true
        assertNull(cache["key"])
        assertEquals("new", cache["key"])
    }
}
