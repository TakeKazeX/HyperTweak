package com.takekazex.hypertweak.hook.base.dex

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Serialize one feature/build, without making cache-only startup wait for an active scan. */
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
    ): T? {
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        if (cacheOnly) {
            if (!lock.tryLock()) return null
        } else lock.lock()
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
            lock.unlock()
        }
    }
}
