package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GoogleLiveStateTest {
    private class Model(@JvmField val enabled: Boolean, @JvmField val nativeOn: Boolean)

    @Test fun modelOverrideRestoresNativeFlagsAcrossRepeatedTogglesAndRetirement() {
        val first = Model(false, true)
        val second = Model(false, false)
        val state = BooleanFieldOverrides()
        val fields = listOf("enabled", "nativeOn").map { Model::class.java.getDeclaredField(it).apply { isAccessible = true } }
        state.capture(first, fields)
        state.apply { true }
        state.capture(first, fields) // A refresh cannot replace native false with the forced true.
        state.capture(second, fields)
        state.apply { it === first }
        assertTrue(first.enabled)
        assertFalse(second.enabled)
        state.restore()
        assertFalse(first.enabled)
        assertTrue(first.nativeOn)
        assertFalse(second.nativeOn)
        state.apply { true }
        state.restore()
        assertFalse(first.enabled)
    }

    @Test fun listRestorationPreservesUnrelatedNativeEditsAndPreexistingMembership() {
        val changed = mutableListOf("google", "native")
        val original = mutableSetOf("google")
        val state = CollectionMembershipOverride("google")
        state.apply(changed, false)
        changed += "new-native"
        state.apply(original, true)
        state.restore()
        assertEquals(setOf("google", "native", "new-native"), changed.toSet())
        assertEquals(setOf("google"), original)
        state.apply(changed, false)
        state.restore()
        assertTrue("google" in changed)
    }

    @Test fun queuedWorkCoalescesAndRetirementPreventsAnOldQueuedCommit() {
        val queued = ArrayList<() -> Unit>()
        val writes = ArrayList<Boolean>()
        val worker = LatestSettingsWork<Boolean>(queued::add, writes::add) { throw it }
        assertFalse(worker.request(true))
        assertFalse(worker.request(false))
        queued.removeAt(0)()
        assertEquals(listOf(false), writes)
        assertTrue(worker.request(false))
        worker.request(true)
        worker.retire()
        queued.removeAt(0)()
        assertEquals(listOf(false), writes)
    }

    @Test fun failedCommitRetriesAndDoesNotAcknowledgeSuccess() {
        val queue = ArrayList<() -> Unit>()
        var attempts = 0
        val errors = ArrayList<Throwable>()
        val worker = LatestSettingsWork<Boolean>(queue::add, { if (++attempts == 1) error("DB locked") }, errors::add)
        worker.request(true)
        queue.removeAt(0)()
        assertEquals(1, errors.size)
        assertFalse(worker.request(true))
        queue.removeAt(0)()
        assertTrue(worker.request(true))
    }

    @Test fun requestDoesNotWaitForInFlightDatabaseCommitAndLatestValueWins() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val writes = java.util.Collections.synchronizedList(ArrayList<Boolean>())
        val work = LatestSettingsWork<Boolean>({ executor.execute(it) }, {
            if (it) { started.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            writes += it
            if (!it) done.countDown()
        }) { throw it }
        try {
            work.request(true)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertFalse(work.request(false))
            release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(true, false), writes)
            assertTrue(work.request(false))
            work.retire()
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
