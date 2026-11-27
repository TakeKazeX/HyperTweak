package com.takekazex.hypertweak.hook.base.dex

import java.io.File
import java.io.RandomAccessFile
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
            var contended = 0
            assertEquals("ran", HostFileLock.withLock(file, waitMs = 200, contendedAfterMs = 50,
                onContended = { contended++ }) { "ran" })
            assertTrue((System.nanoTime() - started) / 1_000_000L >= 200)
            assertEquals(1, contended)
        } finally { release.countDown(); holder.join(3000) }
    }

    /** POSIX record locks belong to the process: a waiter closing its own descriptor used to drop them. */
    @Test fun `a waiter that gives up leaves the holder's lock in force for other processes`() {
        val file = temporary.root.resolve("feature.lock")
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        val holder = Thread { HostFileLock.withLock(file) { held.countDown(); release.await(10, TimeUnit.SECONDS) } }
        holder.start()
        try {
            assertTrue(held.await(3, TimeUnit.SECONDS))
            HostFileLock.withLock(file, waitMs = 100) { }
            assertEquals("busy", probe(file, "try"))
        } finally { release.countDown(); holder.join(3000) }
        assertEquals("locked", probe(file, "try"))
    }

    @Test fun `a sibling process holding the lock is waited for`() {
        val file = temporary.root.resolve("feature.lock")
        val child = start(file, "hold")
        try {
            assertEquals("held", child.inputStream.bufferedReader().readLine())
            val released = Thread { Thread.sleep(300); child.outputStream.apply { write('\n'.code); flush() } }
            released.start()
            val started = System.nanoTime()
            assertEquals("ran", HostFileLock.withLock(file, waitMs = 5_000) { "ran" })
            assertTrue((System.nanoTime() - started) / 1_000_000L >= 250)
            released.join(3000)
        } finally { child.destroy(); child.waitFor(3, TimeUnit.SECONDS) }
    }

    @Test fun `a short wait keeps what the caller holds`() {
        val file = temporary.root.resolve("feature.lock")
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        val holder = Thread { HostFileLock.withLock(file) { held.countDown(); release.await(5, TimeUnit.SECONDS) } }
        holder.start()
        try {
            assertTrue(held.await(3, TimeUnit.SECONDS))
            Thread { Thread.sleep(100); release.countDown() }.start()
            var contended = 0
            HostFileLock.withLock(file, contendedAfterMs = 2_000, onContended = { contended++ }) { }
            assertEquals(0, contended)
        } finally { release.countDown(); holder.join(3000) }
    }

    @Test fun `no lock file means no exclusion`() {
        assertEquals(7, HostFileLock.withLock(null) { 7 })
    }

    private fun start(file: File, mode: String): Process = ProcessBuilder(
        File(System.getProperty("java.home"), "bin/java").path, "-cp", System.getProperty("java.class.path"),
        LockProbe::class.java.name, mode, file.path,
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

    private fun probe(file: File, mode: String): String {
        val process = start(file, mode)
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        return process.inputStream.bufferedReader().readText().trim()
    }
}

/** A separate JVM, standing in for a sibling host process. */
object LockProbe {
    @JvmStatic fun main(args: Array<String>) {
        RandomAccessFile(args[1], "rw").channel.use { channel ->
            when (args[0]) {
                "try" -> println(if (channel.tryLock() != null) "locked" else "busy")
                "hold" -> { channel.lock(); println("held"); System.out.flush(); System.`in`.read() }
            }
        }
    }
}
