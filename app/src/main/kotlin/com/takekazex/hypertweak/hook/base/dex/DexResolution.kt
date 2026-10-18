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
    ): T? {
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        if (cacheOnly) {
            if (!lock.tryLock()) return null
        } else lock.lock()
        try {
            read()?.let { values ->
                runCatching { decode(values, true) }.onFailure(invalid).getOrNull()?.let { return it }
            }
            if (cacheOnly) return null
            val values = scan() ?: return null
            val result = runCatching { decode(values, false) }.onFailure(invalid).getOrNull() ?: return null
            write(values)
            return result
        } finally {
            lock.unlock()
        }
    }
}
