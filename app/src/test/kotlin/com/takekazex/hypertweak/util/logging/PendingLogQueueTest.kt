package com.takekazex.hypertweak.util.logging

import org.junit.Assert.*
import org.junit.Test

class PendingLogQueueTest {
    @Test fun `debug storm cannot evict an earlier warning or error`() {
        val queue = PendingLogQueue<String>(capacity = 2)
        queue.offer("W", "warning", 10)
        queue.offer("E", "exception with stack", 100)
        repeat(1000) { queue.offer("D", "debug-$it", 10) }
        val batch = queue.drain()
        assertEquals(listOf("exception with stack", "warning", "debug-998", "debug-999"), batch.values)
        assertEquals(mapOf("D" to 998L), batch.dropped)
    }

    @Test fun `each severity has a bounded ring and losses remain attributable`() {
        val queue = PendingLogQueue<String>(capacity = 2)
        for (level in listOf("D", "I", "W", "E")) {
            repeat(4) { queue.offer(level, "$level$it", 1) }
        }
        val batch = queue.drain()
        assertEquals(listOf("E2", "E3", "W2", "W3", "I2", "I3", "D2", "D3"), batch.values)
        assertEquals(mapOf("E" to 2L, "W" to 2L, "I" to 2L, "D" to 2L), batch.dropped)
        assertTrue(queue.drain().values.isEmpty())
        assertTrue(queue.drain().dropped.isEmpty())
    }

    @Test fun `large stacks hit the character budget before the record limit`() {
        val queue = PendingLogQueue<String>(capacity = 100, maxCharsPerLevel = 10)
        queue.offer("E", "first stack", 6)
        assertTrue(queue.offer("E", "latest stack", 8))
        queue.offer("D", "oversized", 11)
        val batch = queue.drain()
        assertEquals(listOf("latest stack"), batch.values)
        assertEquals(mapOf("E" to 1L, "D" to 1L), batch.dropped)
        queue.offer("E", "after drain", 10)
        assertEquals(listOf("after drain"), queue.drain().values)
    }

    @Test fun `fatal records share error protection and pressure expedites draining`() {
        val queue = PendingLogQueue<String>(capacity = 4)
        assertFalse(queue.offer("F", "fatal", 1))
        queue.offer("E", "error", 1)
        assertTrue(queue.offer("E", "error2", 1))
        repeat(10) { queue.offer("W", "warning$it", 1) }
        assertEquals(listOf("fatal", "error", "error2"), queue.drain().values.take(3))
    }

    @Test fun `concurrent producers and draining do not silently lose records`() {
        val queue = PendingLogQueue<Int>(capacity = 32)
        val workers = (0..3).map { producer -> Thread {
            repeat(1000) { queue.offer("D", producer * 1000 + it, 1) }
        }.apply { start() } }
        var delivered = 0
        var dropped = 0L
        while (workers.any { it.isAlive }) {
            val batch = queue.drain()
            delivered += batch.values.size
            dropped += batch.dropped.values.sum()
            Thread.yield()
        }
        workers.forEach(Thread::join)
        val final = queue.drain()
        assertEquals(4000L, delivered + dropped + final.values.size + final.dropped.values.sum())
    }
}
