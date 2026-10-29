package com.takekazex.hypertweak.ui.state

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DesktopIconSizeQueueTest {
    @Test fun slowCommitDoesNotDropLatestSizeOrEarlyReset() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val persisted = mutableListOf<Int>()
        try {
            val queue = DesktopIconSizeQueue(scope)
            queue.submit(150) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                synchronized(persisted) { persisted.add(it) }; true
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            for (size in 149 downTo 50) queue.submit(size) { synchronized(persisted) { persisted.add(it) }; true }
            queue.submit(-1) { synchronized(persisted) { persisted.add(it) }; true }
            release.countDown()
            val result = withTimeout(5000) { queue.state.first { it.requested == -1 && !it.saving } }
            assertTrue(result.succeeded)
            assertEquals(listOf(150, -1), synchronized(persisted) { persisted.toList() })
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun failureCanRetrySameValueAndDoesNotKillQueue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = DesktopIconSizeQueue(scope)
            queue.submit(75) { throw IllegalStateException("offline") }
            assertFalse(withTimeout(5000) { queue.state.first { it.revision == 1L && !it.saving } }.succeeded)
            queue.submit(75) { true }
            assertTrue(withTimeout(5000) { queue.state.first { it.revision == 2L && !it.saving } }.succeeded)
        } finally { scope.cancel() }
    }
}
