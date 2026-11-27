package com.takekazex.hypertweak.util

/**
 * Runs [block] and drops its result and any [Throwable], like a `runCatching { }` whose [Result]
 * is deliberately unused. For best-effort cleanup (unregistering, closing, restoring views) and
 * for code that must not throw from a logger. Inline, so a non-local `return` still works.
 *
 * Failures here are invisible. Do not use it where a failure needs to be seen: resolver,
 * persistence and system-callback boundaries log through `onFailure` instead.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun <T> ignoreFailure(block: () -> T) {
    try {
        val _ = block()
    } catch (_: Throwable) {
    }
}
