package com.takekazex.hypertweak.hook.base.dex

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DexPreparationOwnerTest {
    @Test fun `registration racing retirement cannot scan or publish into the retired owner`() {
        val owner = DexPreparationOwner()
        val worker = ArrayDeque<() -> Unit>()
        owner.prepare("race", create = { failure ->
            // Retire before the preparation has been registered or scheduled.
            owner.cancel()
            DexPreparation(background = { worker.add(it) }, publish = { it() }, failure = failure)
        }, failure = { throw it }, resolve = { fail("stale scan"); 1 }, install = { fail("stale install") })
        worker.removeFirst()()
        owner.activate()
        var installed = 0
        owner.prepare("race", { failure -> DexPreparation({ it() }, { it() }, failure = failure) },
            { throw it }, { 2 }, { installed = it })
        assertEquals(2, installed)
    }

    @Test fun `retirement does not wait for resolution and blocks its eventual publication`() {
        val owner = DexPreparationOwner()
        val started = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        owner.prepare("slow", { failure ->
            DexPreparation(background = { action -> Thread { try { action() } finally { done.countDown() } }.start() },
                publish = { it() }, failure = failure)
        }, { throw it }, {
            started.countDown(); check(release.await(3, TimeUnit.SECONDS)); 1
        }, { fail("stale installation") })
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val cancelled = CountDownLatch(1)
        Thread { owner.cancel(); cancelled.countDown() }.start()
        try { assertTrue(cancelled.await(1, TimeUnit.SECONDS)) } finally { release.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
    }

    @Test fun `retired owner refuses new tasks until the next lifecycle activates it`() {
        val owner = DexPreparationOwner()
        owner.cancel()
        owner.prepare<Int>("late", { fail("must not allocate a task"); error("late") },
            { throw it }, { fail("late scan"); 1 }, { fail("late installation") })
    }
}
