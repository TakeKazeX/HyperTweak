package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.DebugLog

/** Small fail-open boundary for code running inside another process. */
object HookFailurePolicy {

    fun <T> open(scope: String, operation: String, fallback: T, block: () -> T): T {
        return try { block() } catch (t: Throwable) {
            DebugLog.e(scope, "hook extension failed operation=$operation", t)
            fallback
        }
    }

}
