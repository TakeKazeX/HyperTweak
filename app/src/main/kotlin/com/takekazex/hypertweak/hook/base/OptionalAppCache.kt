package com.takekazex.hypertweak.hook.base

import java.io.File

/** Storage is an optional capability of a host context, not a property of its Android version. */
internal object OptionalAppCache {
    fun <T> open(directory: () -> File?, create: () -> T): Result<T?> {
        // The framework's system context throws while resolving dataDir; do not call its prefs API.
        if (runCatching(directory).getOrNull() == null) return Result.success(null)
        return runCatching { create() }
    }
}
