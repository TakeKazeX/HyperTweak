package com.takekazex.hypertweak.hook.preferences

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class AcknowledgedSettingsTaskTest {
    @Test fun expiredQueuedTransactionCanNeverCommitLater() {
        val writer = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        var committed = false
        try {
            writer.execute { release.await() }
            try {
                AcknowledgedSettingsTask.run(writer, 20) { committed = true }
                fail("Queued transaction must expire")
            } catch (_: TimeoutException) { }
            release.countDown()
            writer.submit {}.get(2, TimeUnit.SECONDS)
            assertFalse(committed)
        } finally { release.countDown(); writer.shutdownNow() }
    }

    @Test fun startedTransactionReturnsItsReceiptAfterTheQueueDeadline() {
        val writer = Executors.newSingleThreadExecutor()
        val callers = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val result = callers.submit<Boolean> {
                AcknowledgedSettingsTask.run(writer, 20) {
                    entered.countDown(); release.await(); true
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            try { result.get(100, TimeUnit.MILLISECONDS); fail("Receipt must still be pending") }
            catch (_: TimeoutException) { }
            release.countDown()
            assertTrue(result.get(2, TimeUnit.SECONDS))
        } finally { release.countDown(); callers.shutdownNow(); writer.shutdownNow() }
    }

    @Test fun interruptionAfterStartDoesNotInventFailureAndPreservesInterruptFlag() {
        val writer = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val receipt = AtomicBoolean()
        val interrupted = AtomicBoolean()
        val caller = Thread {
            receipt.set(AcknowledgedSettingsTask.run(writer) { entered.countDown(); release.await(); true })
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        try {
            caller.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            caller.interrupt()
            release.countDown()
            caller.join(2_000)
            assertFalse(caller.isAlive)
            assertTrue(receipt.get())
            assertTrue(interrupted.get())
        } finally { release.countDown(); caller.join(2_000); writer.shutdownNow() }
    }
}
