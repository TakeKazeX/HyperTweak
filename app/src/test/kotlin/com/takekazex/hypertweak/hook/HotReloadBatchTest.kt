package com.takekazex.hypertweak.hook

import org.junit.Assert.*
import org.junit.Test

class HotReloadBatchTest {
    @Test fun serialRequestsWaitForCallbackAndIgnoreDuplicates() {
        val submitted = mutableListOf<Int>()
        val callbacks = mutableListOf<(String) -> Unit>()
        var result: List<String>? = null
        val batch = HotReloadBatch(listOf(1, 2), { target, callback ->
            submitted += target; callbacks += callback
        }, { _, _ -> "error" }, { result = it })
        batch.start()
        batch.start()
        assertEquals(listOf(1), submitted)
        callbacks[0]("one")
        assertEquals(listOf(1, 2), submitted)
        callbacks[0]("duplicate")
        assertNull(result)
        callbacks[1]("two")
        callbacks[1]("duplicate")
        assertEquals(listOf("one", "two"), result)
    }

    @Test fun synchronousCallbacksDoNotRecurseAndFailuresContinue() {
        var result: List<Int>? = null
        var finished = 0
        val targets = (1..10000).toList()
        HotReloadBatch(targets, { target, callback ->
            if (target == 2) error("request rejected")
            callback(target)
        }, { target, _ -> -target }, { result = it; finished++ }).start()
        assertEquals(10000, result?.size)
        assertEquals(-2, result?.get(1))
        assertEquals(1, finished)
    }

    @Test fun serviceDeathCancelsQueuedRequestsAndLateResults() {
        var submitted = 0
        var callback: ((String) -> Unit)? = null
        var finished = false
        val batch = HotReloadBatch(listOf(1, 2), { _, accept -> submitted++; callback = accept },
            { _, _ -> "error" }, { finished = true })
        batch.start()
        batch.cancel()
        callback?.invoke("late")
        batch.start()
        assertEquals(1, submitted)
        assertFalse(finished)
    }

    @Test fun callbackFollowedByThrowDoesNotCompleteTwice() {
        var completed = 0
        HotReloadBatch(listOf(1), { _, callback -> callback("ok"); error("late exception") },
            { _, _ -> "error" }, { assertEquals(listOf("ok"), it); completed++ }).start()
        assertEquals(1, completed)
    }

    @Test fun exitedAndBusyTargetsAreNeitherFailedNorSuccessful() {
        val report = HotReloadReport(listOf("same", "same", "busy", "failed"), listOf(
            HotReloadTargetReport("same", true, pid = 1),
            HotReloadTargetReport("same", false, pid = 2, outcome = HotReloadOutcome.PROCESS_EXITED),
            HotReloadTargetReport("busy", false, outcome = HotReloadOutcome.IN_PROGRESS),
            HotReloadTargetReport("failed", false)))
        assertEquals(1, report.succeededCount)
        assertEquals(1, report.failedCount)
        assertEquals(1, report.exitedCount)
        assertEquals(1, report.pendingCount)
        assertEquals(listOf(1, 2), report.results.take(2).map { it.pid })
    }
}
