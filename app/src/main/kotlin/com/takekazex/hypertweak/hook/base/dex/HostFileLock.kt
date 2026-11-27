package com.takekazex.hypertweak.hook.base.dex

import com.takekazex.hypertweak.util.logging.DebugLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

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
 * the host main thread never waits: it tries once and proceeds.
 *
 * Callers keep one holder per process and file (`DexResolution`'s per-key lock, `ApkDigestStore`'s
 * hash monitor). The one exception is a hot reload, where the old and new generation each have their
 * own resolver: their channels may overlap, which at worst repeats a scan.
 */
internal object HostFileLock {
    const val WAIT_MS = 30_000L
    private const val POLL_MS = 50L

    fun <T> withLock(file: File?, waitMs: Long = WAIT_MS, block: () -> T): T {
        val target = file ?: return block()
        val onMain = runCatching {
            android.os.Looper.getMainLooper()?.let { it === android.os.Looper.myLooper() } == true
        }.getOrDefault(false)
        val channel = runCatching {
            target.parentFile?.takeIf { !it.isDirectory }?.mkdirs()
            RandomAccessFile(target, "rw").channel
        }.onFailure { DebugLog.d(TAG, "lock unavailable " + target.name + ": " + it.message) }
            .getOrNull() ?: return block()
        try {
            val started = System.nanoTime()
            var lock: FileLock? = null
            // Every exit of the wait (acquired, main-thread pass-through, timeout) is "lock" time.
            fun waited() = DexPhases.add("lock", System.nanoTime() - started)
            while (true) {
                lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) {
                    null // Another thread of this process holds it; wait like a sibling would.
                }
                if (lock != null) break
                val waited = (System.nanoTime() - started) / 1_000_000L
                if (onMain || waited >= waitMs) {
                    waited()
                    if (!onMain) DebugLog.w(TAG, "lock " + target.name + " still held after " + waited + "ms; continuing unlocked")
                    return block()
                }
                Thread.sleep(POLL_MS)
            }
            waited()
            val waited = (System.nanoTime() - started) / 1_000_000L
            if (waited >= POLL_MS) DebugLog.d(TAG, "lock " + target.name + " acquired after " + waited + "ms")
            try { return block() } finally { release(target) { lock.release() } }
        } finally {
            release(target) { channel.close() }
        }
    }

    /** Closing the channel (or process death) also drops the lock, so a failure here only logs. */
    private inline fun release(target: File, action: () -> Unit) {
        try { action() } catch (error: java.io.IOException) {
            DebugLog.d(TAG, "release " + target.name + ": " + error.message)
        }
    }

    private const val TAG = "HostFileLock"
}
