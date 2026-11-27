package com.takekazex.hypertweak.hook.base.dex

import com.takekazex.hypertweak.util.logging.DebugLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Mutual exclusion between sibling processes of one host, through an advisory lock file in that
 * host's own cache directory.
 *
 * Without it every process of a multi-process host scanned the same cold feature at the same time:
 * Security Center's five processes each hashed its 81 MB APK and each ran `gamebooster.gunsight`
 * (5-11 s), because none could see a cache that the others had not written yet.
 *
 * The lock only orders work; it never decides a result. The kernel drops it when its holder dies,
 * a waiting thread gives up after [WAIT_MS] and runs [block] unlocked (the pre-lock behavior), and
 * the host main thread never waits or touches the file: it runs [block] unlocked at once.
 *
 * The kernel lock is a POSIX record lock, which belongs to the process, not to a descriptor:
 * closing *any* descriptor of the file drops every lock the process holds on it. Threads of this
 * generation therefore queue on an in-process lock per file first, and only its holder opens a
 * descriptor; a waiter that gives up has opened nothing, so it cannot release the holder's lock.
 * The one remaining overlap is a hot reload, where the old and new generation each have their own
 * in-process locks; the JVM's shared lock table reports that as [OverlappingFileLockException],
 * waited on like a sibling, and at worst a timed-out waiter repeats a scan.
 */
internal object HostFileLock {
    const val WAIT_MS = 30_000L
    private const val POLL_MS = 50L
    private const val TAG = "HostFileLock"
    private val local = ConcurrentHashMap<String, ReentrantLock>()

    /** Waits shorter than this keep what the caller holds; reopening a bridge can cost seconds. */
    const val CONTENDED_MS = 500L

    /**
     * [onContended] runs once, on the waiting thread, when the wait passes [contendedAfterMs]: a
     * caller releases what it should not hold through a long wait (an idle DexKit bridge). Short
     * waits, the common case when siblings take turns, keep it, so the bridge does not thrash.
     */
    fun <T> withLock(
        file: File?,
        waitMs: Long = WAIT_MS,
        contendedAfterMs: Long = CONTENDED_MS,
        onContended: () -> Unit = {},
        block: () -> T,
    ): T {
        val target = file ?: return block()
        if (onMainThread()) return block()
        val started = System.nanoTime()
        fun waitedMs() = (System.nanoTime() - started) / 1_000_000L
        var contended = false
        fun contend() {
            if (contended) return
            contended = true
            runCatching(onContended).onFailure { DebugLog.d(TAG, "contention callback failed: " + it.message) }
        }
        val inProcess = local.computeIfAbsent(target.absolutePath) { ReentrantLock() }
        // Already held by this thread (a nested resolution): its descriptor and kernel lock cover us.
        if (inProcess.isHeldByCurrentThread) return block()
        if (!inProcess.tryLock(minOf(contendedAfterMs, waitMs), TimeUnit.MILLISECONDS)) {
            contend()
            if (!inProcess.tryLock((waitMs - waitedMs()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)) {
                DexPhases.add("lock", System.nanoTime() - started)
                DebugLog.w(TAG, "lock " + target.name + " still held in process after " + waitedMs() + "ms; continuing unlocked")
                return block()
            }
        }
        try {
            val channel = runCatching {
                target.parentFile?.takeIf { !it.isDirectory }?.mkdirs()
                RandomAccessFile(target, "rw").channel
            }.onFailure { DebugLog.d(TAG, "lock unavailable " + target.name + ": " + it.message) }
                .getOrNull()
            if (channel == null) {
                DexPhases.add("lock", System.nanoTime() - started)
                return block()
            }
            try {
                var lock: FileLock?
                while (true) {
                    lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) {
                        null // The other generation of this process holds it; wait like a sibling.
                    }
                    if (lock != null) break
                    if (waitedMs() >= waitMs) {
                        DexPhases.add("lock", System.nanoTime() - started)
                        DebugLog.w(TAG, "lock " + target.name + " still held after " + waitedMs() + "ms; continuing unlocked")
                        return block()
                    }
                    if (waitedMs() >= contendedAfterMs) contend()
                    Thread.sleep(POLL_MS)
                }
                DexPhases.add("lock", System.nanoTime() - started)
                if (waitedMs() >= POLL_MS) DebugLog.d(TAG, "lock " + target.name + " acquired after " + waitedMs() + "ms")
                try { return block() } finally { release(target) { lock.release() } }
            } finally {
                release(target) { channel.close() }
            }
        } finally {
            inProcess.unlock()
        }
    }

    private fun onMainThread(): Boolean = runCatching {
        android.os.Looper.getMainLooper()?.let { it === android.os.Looper.myLooper() } == true
    }.getOrDefault(false)

    /** Closing the channel (or process death) also drops the lock, so a failure here only logs. */
    private inline fun release(target: File, action: () -> Unit) {
        try { action() } catch (error: java.io.IOException) {
            DebugLog.d(TAG, "release " + target.name + ": " + error.message)
        }
    }
}
