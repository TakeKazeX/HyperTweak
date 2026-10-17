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
}
