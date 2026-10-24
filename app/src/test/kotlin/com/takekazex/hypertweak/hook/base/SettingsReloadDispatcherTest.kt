package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test

class SettingsReloadDispatcherTest {
    private class Loop {
        val tasks = linkedMapOf<Runnable, Long>()
        val failures = arrayListOf<String>()
        val reload = SettingsReloadDispatcher({ task, delay -> tasks[task] = delay },
            { tasks.remove(it) }, { id, _ -> failures += id })
        fun tick() { val work = tasks.keys.toList(); tasks.clear(); work.forEach(Runnable::run) }
    }
    @Test fun rapidTogglesCoalesceAndApplyLatestCommittedValueOnce() {
        val loop = Loop()
        var enabled = false
        val applied = arrayListOf<Boolean>()
        loop.reload.register("island") { applied += enabled; true }
        repeat(10) { enabled = !enabled; loop.reload.request(setOf("island")) }
        assertEquals(1, loop.tasks.size)
        loop.tick()
        assertEquals(listOf(false), applied)
        assertTrue(loop.tasks.isEmpty())
        enabled = true
        loop.reload.request(setOf("island")); loop.tick()
        assertEquals(listOf(false, true), applied)
    }
    @Test fun unavailableHostsRetryFinitelyWithoutBlockingOtherParticipants() {
        val loop = Loop()
        var attempts = 0
        var icons = 0
        loop.reload.register("island") { attempts++; false }
        loop.reload.register("icons") { icons++; true }
        loop.reload.request(setOf("island", "icons"))
        repeat(8) { loop.tick() }
        assertEquals(5, attempts)
        assertEquals(1, icons)
        assertEquals(listOf("island"), loop.failures)
        assertTrue(loop.tasks.isEmpty())
    }
    @Test fun retirementCancelsQueuedWorkAndFailedPreparationCanResume() {
        val loop = Loop()
        var applied = 0
        loop.reload.register("island") { applied++; true }
        loop.reload.request(setOf("island")); loop.reload.cancel(); loop.tick()
        loop.reload.request(setOf("island")); loop.tick()
        assertEquals(0, applied)
        loop.reload.resume(); loop.tick()
        assertEquals(1, applied)
    }
    @Test fun oneFailureDoesNotPreventOtherGroupsFromRestoring() {
        val loop = Loop()
        var restored = false
        loop.reload.register("island") { error("missing host contract") }
        loop.reload.register("icons") { restored = true; true }
        loop.reload.request(setOf("island", "icons")); loop.tick()
        assertTrue(restored)
        assertEquals(listOf("island"), loop.failures)
    }
    @Test fun fullRefreshSurvivesKeyedChangesAndRetry() {
        val loop = Loop()
        val seen = arrayListOf<Set<String>>()
        loop.reload.register("header") { keys -> seen += keys; seen.size > 1 }
        loop.reload.resume()
        loop.reload.request(setOf("header"), setOf("notification_header_hide_date"))
        loop.tick()
        assertEquals(listOf(emptySet<String>()), seen)
        loop.reload.request(setOf("header"), setOf("notification_header_hide_time"))
        loop.tick()
        assertEquals(listOf(emptySet<String>(), emptySet<String>()), seen)
    }

    @Test fun keyedRefreshNeverRunsAnotherGroupsFeatureKeys() {
        val loop = Loop()
        val seen = linkedMapOf<String, Set<String>>()
        loop.reload.register("header") { keys -> seen["header"] = keys; true }
        loop.reload.register("lockscreen") { keys -> seen["lockscreen"] = keys; true }
        loop.reload.request(setOf("header"), setOf("seconds"))
        loop.reload.request(setOf("lockscreen"), setOf("bottom_text"))
        loop.tick()
        assertEquals(setOf("seconds"), seen["header"])
        assertEquals(setOf("bottom_text"), seen["lockscreen"])
    }

    @Test fun asyncPublicationRemainsPendingPastTheNormalRetryWindow() {
        val loop = Loop()
        var published = false
        var attempts = 0
        loop.reload.registerWithRetryBudget("dock", 12) { attempts++; published }
        loop.reload.request(setOf("dock"))
        repeat(8) { loop.tick() }
        assertEquals(8, attempts)
        assertTrue(loop.failures.isEmpty())
        assertTrue(loop.tasks.isNotEmpty())
        published = true
        loop.tick()
        assertTrue(loop.tasks.isEmpty())
    }

    @Test fun anAsyncOwnerStillHasAFiniteFailureBudget() {
        val loop = Loop()
        loop.reload.registerWithRetryBudget("dock", 12) { false }
        loop.reload.request(setOf("dock"))
        repeat(15) { loop.tick() }
        assertEquals(listOf("dock"), loop.failures)
        assertTrue(loop.tasks.isEmpty())
    }

}
