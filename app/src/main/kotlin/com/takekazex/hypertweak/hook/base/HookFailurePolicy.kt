package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Small fail-open boundary for code running inside another process. */
object HookFailurePolicy {

    // Inline: callers sit on per-frame host paths, and a non-inline boundary allocated a closure
    // per call.
    inline fun <T> open(scope: String, operation: String, fallback: T, block: () -> T): T {
        return try { block() } catch (t: Throwable) {
            report(scope, operation, t)
            fallback
        }
    }

    private val failures = ConcurrentHashMap<String, AtomicLong>()

    /** Failure path only; repeated failures of one operation follow [FailureLogBudget]. */
    @PublishedApi internal fun report(scope: String, operation: String, t: Throwable) {
        val count = failures.computeIfAbsent("$scope\u0000$operation") { AtomicLong() }.incrementAndGet()
        when (FailureLogBudget.entry(count)) {
            FailureLogBudget.Entry.FULL -> DebugLog.e(scope, "hook extension failed operation=$operation", t)
            FailureLogBudget.Entry.SUMMARY -> DebugLog.e(scope, "hook extension failed operation=$operation " +
                "failures=$count last=${t.javaClass.name}: ${t.message}")
            FailureLogBudget.Entry.NONE -> Unit
        }
    }
}
