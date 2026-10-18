package com.takekazex.hypertweak.hook.base

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Storage is an optional capability of a host context, not a property of its Android version. */
internal object OptionalAppCache {
    fun <T> open(directory: () -> File?, create: () -> T): Result<T?> {
        // The framework's system context throws while resolving dataDir; do not call its prefs API.
        if (runCatching(directory).getOrNull() == null) return Result.success(null)
        return runCatching { create() }
    }
}

/** Failed optional storage is retried on demand, never on every preference read or recursively. */
internal class DeferredAppCache<T : Any>(private val clock: () -> Long, private val retryMs: Long = 5_000L) {
    @Volatile private var cached: T? = null
    @Volatile private var retryAt = Long.MIN_VALUE
    private val opening = AtomicBoolean()

    fun open(create: () -> Result<T?>): Result<T?> {
        cached?.let { return Result.success(it) }
        if (clock() < retryAt || !opening.compareAndSet(false, true)) return Result.success(null)
        return try {
            cached?.let { return Result.success(it) }
            if (clock() < retryAt) return Result.success(null)
            val result = runCatching { create().getOrThrow() }
            cached = result.getOrNull()
            if (cached == null) retryAt = clock() + retryMs
            result
        } finally {
            opening.set(false)
        }
    }
}
