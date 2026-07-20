package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.ConcurrentHashMap

/** Small fail-open boundary for code running inside another process. */
object HookFailurePolicy {
    private val reported = ConcurrentHashMap.newKeySet<String>()

    fun <T> open(scope: String, operation: String, fallback: T, block: () -> T): T {
        return try { block() } catch (t: Throwable) {
            if (reported.add("$scope:$operation")) DebugLog.w(scope, "hook extension failed operation=$operation", t)
            fallback
        }
    }

    fun reset() = reported.clear()
}
