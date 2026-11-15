package com.takekazex.hypertweak.hook.rules.sms

import org.junit.Assert.*
import org.junit.Test

class SmsCopyQueueTest {
    private fun queue() = SmsCopyQueue().apply { enable(true) }
    private fun SmsCopyQueue.receive(id: Long, code: String, time: Long = 100L): Boolean =
        begin(id, time, time + 1_000)?.let { offer(it, code, time) } ?: false

    @Test fun `same message is copied once but distinct messages may carry the same code`() {
        val queue = queue()
        assertTrue(queue.receive(1, "123456"))
        val first = queue.peek(101)!!
        assertEquals("123456", queue.take(first, 101, null))
        assertNull(queue.take(first, 102, null))
        assertFalse(queue.receive(1, "123456", 103))
        assertTrue(queue.receive(2, "123456", 104))
    }

    @Test fun `a late recognition cannot replace a newer code`() {
        val queue = queue()
        val old = queue.begin(1, 100, 1_100)!!
        val recent = queue.begin(2, 101, 1_101)!!
        assertTrue(queue.offer(recent, "222222", 102))
        assertFalse(queue.offer(old, "111111", 103))
        assertEquals("222222", queue.peek(104)?.code)
    }

    @Test fun `an unrelated or unrecognized message does not discard the latest code`() {
        val queue = queue()
        assertTrue(queue.receive(1, "123456"))
        assertFalse(queue.receive(2, "", 101))
        assertFalse(queue.receive(3, "  ", 102))
        assertEquals("123456", queue.peek(103)?.code)
    }

    @Test fun `locked pending code expires exactly at two minutes and never replays`() {
        val queue = queue()
        assertTrue(queue.receive(1, "123456"))
        assertNotNull(queue.peek(100 + SmsCopyQueue.MAX_AGE_MS - 1))
        assertNull(queue.peek(100 + SmsCopyQueue.MAX_AGE_MS))
        assertFalse(queue.receive(1, "123456", 100 + SmsCopyQueue.MAX_AGE_MS + 1))
    }

    @Test fun `turning off or resetting cancels in-flight recognition and locked pending work`() {
        val queue = queue()
        val inFlight = queue.begin(1, 100, 1_100)!!
        assertTrue(queue.receive(2, "222222", 101))
        queue.enable(false)
        assertNull(queue.peek(102))
        assertNull(queue.begin(3, 103, 1_103))
        queue.enable(true)
        assertFalse(queue.offer(inFlight, "111111", 104))
        assertFalse(queue.receive(2, "222222", 105))
        assertTrue(queue.receive(3, "333333", 106))
    }

    @Test fun `clipboard update after receipt suppresses delayed publication`() {
        val queue = queue()
        assertTrue(queue.receive(1, "123456"))
        val pending = queue.peek(101)!!
        assertNull(queue.take(pending, 101, 1_101))
        assertNull(queue.peek(102))
    }

    @Test fun `unchanged clipboard metadata permits publication and stale posted tasks cannot consume newer SMS`() {
        val queue = queue()
        assertTrue(queue.receive(1, "111111"))
        val oldTask = queue.peek(101)!!
        assertTrue(queue.receive(2, "222222", 102))
        assertNull(queue.take(oldTask, 103, null))
        val latest = queue.peek(103)!!
        assertEquals("222222", queue.take(latest, 104, 1_100))
    }

    @Test fun `hot replacement carries pending code and deduplication using plain JDK values`() {
        val queue = queue()
        assertTrue(queue.receive(1, "111111"))
        assertTrue(queue.receive(2, "222222", 101))
        val replacement = queue()
        replacement.restore(queue.save(), 102)
        assertFalse(replacement.receive(1, "111111", 103))
        assertFalse(replacement.receive(2, "222222", 104))
        assertEquals("222222", replacement.peek(105)?.code)
        assertTrue(replacement.receive(3, "333333", 106))
        assertEquals("333333", replacement.peek(107)?.code)
    }

    @Test fun `upgrades with no state stay empty and expired saved work is dropped`() {
        val queue = queue()
        queue.restore(null, 100)
        assertNull(queue.peek(100))
        assertTrue(queue.receive(1, "111111"))
        val replacement = queue()
        replacement.restore(queue.save(), 100 + SmsCopyQueue.MAX_AGE_MS)
        assertNull(replacement.peek(100 + SmsCopyQueue.MAX_AGE_MS))
        assertFalse(replacement.receive(1, "111111", 100 + SmsCopyQueue.MAX_AGE_MS + 1))
    }

    @Test fun `invalid message identity or clock reversal never publishes`() {
        val queue = queue()
        assertNull(queue.begin(0, 100, 1_100))
        assertFalse(queue.offer(queue.begin(1, 100, 1_100)!!, "123456", 99))
    }
}
