package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.logging.DebugLog

/** Small fail-open boundary for code running inside another process. */
object HookFailurePolicy {

    // Inline: callers sit on per-frame host paths, and a non-inline boundary allocated a closure
    // per call.
    inline fun <T> open(scope: String, operation: String, fallback: T, block: () -> T): T {
        return try { block() } catch (t: Throwable) {
            DebugLog.e(scope, "hook extension failed operation=$operation", t)
            fallback
        }
    }

}
