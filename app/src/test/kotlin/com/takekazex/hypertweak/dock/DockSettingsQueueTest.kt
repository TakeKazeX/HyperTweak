package com.takekazex.hypertweak.dock

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DockSettingsQueueTest {
    @Test fun finalSliderValueIsSavedWithoutAnEndGestureOrPageLifetime() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val persisted = mutableListOf<String>()
        try {
            val queue = DockSettingsQueue(scope) { synchronized(persisted) { persisted.add(it) }; true }
            for (height in 95..130) queue.submit(DockConfig(enabled = true, height = height).encode())
            val result = withTimeout(5000) { queue.state.first { it.revision == 36L && !it.saving } }
            assertFalse(result.failed)
            assertEquals(DockConfig(enabled = true, height = 130).encode(), result.acknowledged)
            assertEquals(result.acknowledged, synchronized(persisted) { persisted.last() })
        } finally { scope.cancel() }
    }

    @Test fun rejectedOrThrowingCommitDoesNotKillFutureSaves() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val attempts = AtomicInteger()
        try {
            val queue = DockSettingsQueue(scope) {
                when (attempts.incrementAndGet()) {
                    1 -> false
                    2 -> throw IllegalStateException("backend unavailable")
                    else -> true
                }
            }
            for (revision in 1L..3L) {
                queue.submit(DockConfig(height = 100 + revision.toInt()).encode())
                val result = withTimeout(5000) { queue.state.first { it.revision == revision && !it.saving } }
                assertEquals(revision < 3, result.failed)
            }
            assertEquals(DockConfig(height = 103).encode(), queue.state.value.acknowledged)
        } finally { scope.cancel() }
    }

    @Test fun returningToAnEarlierValueStillWaitsForTheLatestTransaction() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstEntered = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondRelease = CountDownLatch(1)
        val commits = AtomicInteger()
        try {
            val queue = DockSettingsQueue(scope) {
                if (commits.incrementAndGet() == 1) {
                    firstEntered.countDown(); check(firstRelease.await(5, TimeUnit.SECONDS))
                } else {
                    secondEntered.countDown(); check(secondRelease.await(5, TimeUnit.SECONDS))
                }
                true
            }
            val original = DockConfig(height = 130).encode()
            queue.submit(original)
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            queue.submit(DockConfig(height = 140).encode())
            queue.submit(original)
            firstRelease.countDown()
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            assertTrue("Earlier identical commit cannot acknowledge the last request", queue.state.value.saving)
            secondRelease.countDown()
            val result = withTimeout(5000) { queue.state.first { it.revision == 3L && !it.saving } }
            assertEquals(original, result.acknowledged)
            assertFalse(result.failed)
        } finally { firstRelease.countDown(); secondRelease.countDown(); scope.cancel() }
    }
}
