package com.takekazex.hypertweak.hook.base

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RetiringWorkerTest {
    @Test fun retirementRejectsNewAndQueuedWorkAndNewOwnerCanStart() {
        repeat(8) {
            val started = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val count = AtomicInteger()
            val worker = RetiringWorker("test-assets")
            assertTrue(worker.execute {
                started.countDown()
                try { CountDownLatch(1).await() } catch (_: InterruptedException) { }
                finally { exited.countDown() }
            })
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(worker.execute { count.incrementAndGet() })
            worker.close()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            assertFalse(worker.execute { count.incrementAndGet() })
            assertEquals(0, count.get())
            val next = RetiringWorker("test-assets-next")
            val done = CountDownLatch(1)
            assertTrue(next.execute { done.countDown() })
            assertTrue(done.await(2, TimeUnit.SECONDS))
            next.close()
        }
    }
}
