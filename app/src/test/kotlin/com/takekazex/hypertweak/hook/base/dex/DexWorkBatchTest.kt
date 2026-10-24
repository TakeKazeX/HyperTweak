package com.takekazex.hypertweak.hook.base.dex

import org.junit.Assert.*
import org.junit.Test

class DexWorkBatchTest {
    @Test fun `adjacent features share one session and idle releases it`() {
        val scheduled = ArrayList<Runnable>()
        val events = ArrayList<String>()
        val batch = DexWorkBatch({ scheduled += it }, { path, action ->
            events += "open:$path"
            try { action() } finally { events += "close:$path" }
        }, { throw it })
        batch.submit("base") { events += "first" }
        batch.submit("base") { events += "second" }
        batch.submit("plugin") { events += "plugin" }
        assertEquals(1, scheduled.size)
        scheduled.removeAt(0).run()
        assertEquals(listOf("open:base", "first", "second", "close:base", "open:plugin", "plugin", "close:plugin"), events)
        batch.submit("base") { events += "new" }
        assertEquals(1, scheduled.size)
        scheduled.single().run()
        assertEquals(listOf("open:base", "new", "close:base"), events.takeLast(3))
    }

    @Test fun `one failed preparation cannot strand later features`() {
        val scheduled = ArrayList<Runnable>()
        var failures = 0
        var completed = false
        val batch = DexWorkBatch({ scheduled += it }, { _, action -> action() }, { failures++ })
        batch.submit("base") { error("bad target") }
        batch.submit("base") { completed = true }
        scheduled.single().run()
        assertEquals(1, failures)
        assertTrue(completed)
    }

    @Test fun `rejected scheduling is recoverable and cannot later run stale work`() {
        var reject = true
        val scheduled = ArrayList<Runnable>()
        val batch = DexWorkBatch({ if (reject) error("executor unavailable") else scheduled += it },
            { _, action -> action() }, { throw it })
        assertThrows(IllegalStateException::class.java) { batch.submit("base") { fail("stale") } }
        reject = false
        var completed = false
        batch.submit("base") { completed = true }
        scheduled.single().run()
        assertTrue(completed)
    }
}
