package com.takekazex.hypertweak.presentation.icon

import org.junit.Assert.*
import org.junit.Test

class ByteBudgetCacheTest {
    @Test fun byteAndCountBudgetsEvictLeastRecentlyUsedWithoutDestroyingBorrowedValues() {
        val cache = ByteBudgetCache<String, ByteArray>(2, 10) { it.size.toLong() }
        val a = ByteArray(4)
        cache["a"] = a; cache["b"] = ByteArray(4)
        assertSame(a, cache["a"])
        cache["c"] = ByteArray(5)
        assertNull(cache["b"]); assertSame(a, cache["a"])
        assertEquals(9L, cache.retainedBytes)
        cache["a"] = ByteArray(20)
        assertNull(cache["a"]); assertEquals(5L, cache.retainedBytes)
        assertEquals(4, a.size)
        cache["d"] = ByteArray(0); cache["e"] = ByteArray(0)
        assertNull(cache["c"]); assertEquals(2, cache.size)
        cache.clear(); assertEquals(0L, cache.retainedBytes)
    }
}
