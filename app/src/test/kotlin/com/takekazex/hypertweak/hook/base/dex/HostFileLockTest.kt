package com.takekazex.hypertweak.hook.base.dex

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HostFileLockTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `holders of one lock file never overlap`() {
        val file = temporary.root.resolve("feature.lock")
        val inside = AtomicInteger(); val maxInside = AtomicInteger()
        val pool = Executors.newFixedThreadPool(3)
        try {
            (1..3).map { pool.submit { HostFileLock.withLock(file) {
                maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf); Thread.sleep(120); inside.decrementAndGet()
            } } }.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(1, maxInside.get())
    }

    @Test fun `a waiter gives up after its bound and runs unlocked`() {
        val file = temporary.root.resolve("feature.lock")
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        val holder = Thread { HostFileLock.withLock(file) { held.countDown(); release.await(5, TimeUnit.SECONDS) } }
        holder.start()
        try {
            assertTrue(held.await(3, TimeUnit.SECONDS))
            val started = System.nanoTime()
            assertEquals("ran", HostFileLock.withLock(file, waitMs = 200) { "ran" })
            assertTrue((System.nanoTime() - started) / 1_000_000L >= 200)
        } finally { release.countDown(); holder.join(3000) }
    }

    @Test fun `no lock file means no exclusion`() {
        assertEquals(7, HostFileLock.withLock(null) { 7 })
    }
}
