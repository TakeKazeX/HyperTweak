package com.takekazex.hypertweak.hook.base.dex

import org.junit.Assert.*
import org.junit.Test

class DexPreparationTest {
    private class Queue {
        val tasks = ArrayDeque<() -> Unit>()
        fun post(task: () -> Unit) { tasks.add(task) }
        fun run() { tasks.removeFirst()() }
    }

    @Test fun `resolution is deferred and publishes separately without duplicate pending work`() {
        val worker = Queue(); val main = Queue()
        val task = DexPreparation(worker::post, main::post) { throw it }
        var scans = 0; var installed = 0
        repeat(3) { task.schedule({ ++scans }) { installed = it } }
        assertEquals(0, scans)
        worker.run()
        assertEquals(1, scans)
        assertEquals(0, installed)
        task.schedule({ ++scans }) { installed = it }
        assertTrue(worker.tasks.isEmpty())
        main.run()
        assertEquals(1, installed)
    }

    @Test fun `retirement before worker runs skips scanning`() {
        val worker = Queue(); val main = Queue()
        val task = DexPreparation(worker::post, main::post) { throw it }
        task.schedule({ fail("retired scan must not run"); 1 }) { fail("retired install") }
        task.cancel()
        worker.run()
        assertTrue(main.tasks.isEmpty())
    }

    @Test fun `retirement during scan cannot publish or clear a newer pending generation`() {
        val worker = Queue(); val main = Queue()
        val task = DexPreparation(worker::post, main::post) { throw it }
        var installed = 0
        task.schedule({
            task.cancel()
            task.schedule({ 2 }) { installed = it }
            1
        }) { fail("stale publication") }
        worker.run()
        task.schedule({ fail("new generation is still pending"); 3 }) {}
        assertEquals(1, worker.tasks.size)
        worker.run(); main.run()
        assertEquals(2, installed)
    }

    @Test fun `retirement after scan rejects queued main publication`() {
        val worker = Queue(); val main = Queue()
        val task = DexPreparation(worker::post, main::post) { throw it }
        task.schedule({ 1 }) { fail("publication after retirement") }
        worker.run(); task.cancel(); main.run()
    }

    @Test fun `failed scan and rejected queue release pending ownership`() {
        val main = Queue(); var reject = true; var failures = 0; var installed = 0
        val task = DexPreparation({ action -> if (reject) error("queue full") else action() }, main::post) { failures++ }
        task.schedule({ 1 }) {}
        reject = false
        task.schedule<Int>({ error("scan failed") }) {}
        task.schedule({ 2 }) { installed = it }
        main.run()
        assertEquals(2, failures)
        assertEquals(2, installed)
    }
}
