package com.takekazex.hypertweak.hook.base.dex

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Serialize one feature/build, without making cache-only startup wait for an active scan. A caller
 * that must not wait ([wait] false: the host main thread) never queues behind another thread's scan,
 * which may itself be waiting for a sibling process: on contention it resolves without the key lock,
 * at worst repeating that scan.
 */
internal class DexResolution {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T : Any> resolve(
        key: String,
        cacheOnly: Boolean,
        read: () -> Map<String, String>?,
        scan: () -> Map<String, String>?,
        decode: (Map<String, String>, Boolean) -> T?,
        write: (Map<String, String>) -> Unit,
        invalid: (Throwable) -> Unit,
        /** Cross-process exclusion around re-read, scan and write; see [HostFileLock]. */
        exclusive: (() -> T?) -> T? = { it() },
        wait: Boolean = true,
    ): T? {
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        val locked = if (cacheOnly || !wait) lock.tryLock() else { lock.lock(); true }
        if (!locked && cacheOnly) return null
        try {
            val first = read()
            first?.let { values ->
                runCatching { decode(values, true) }.onFailure(invalid).getOrNull()?.let { return it }
            }
            if (cacheOnly) return null
            return exclusive {
                // A sibling process may have published this feature while we waited for the lock.
                // An unchanged (already rejected) entry is not decoded, or reported, a second time.
                read()?.takeIf { it != first }?.let { values ->
                    runCatching { decode(values, true) }.onFailure(invalid).getOrNull()?.let { return@exclusive it }
                }
                val values = scan() ?: return@exclusive null
                val result = runCatching { decode(values, false) }.onFailure(invalid).getOrNull() ?: return@exclusive null
                write(values)
                result
            }
        } finally {
            if (locked) lock.unlock()
        }
    }
}
