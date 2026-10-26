package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.rules.systemui.gesturebar.GestureBarDeadlines
import com.takekazex.hypertweak.hook.rules.systemui.gesturebar.GestureBarHomeProbe
import com.takekazex.hypertweak.hook.rules.systemui.gesturebar.GestureBarScheduler
import com.takekazex.hypertweak.hook.rules.systemui.gesturebar.GestureBarState
import com.takekazex.hypertweak.hook.rules.systemui.gesturebar.LauncherHomeProbe
import org.junit.Assert.*
import org.junit.Test

internal class TestGestureScheduler : GestureBarScheduler {
    var time = 1000L
    var accepting = true
    val tasks = linkedMapOf<Runnable, Long>()
    override fun now() = time
    override fun post(task: Runnable, delay: Long): Boolean {
        if (accepting) tasks[task] = delay
        return accepting
    }
    override fun remove(task: Runnable) { tasks.remove(task) }
    override fun contains(task: Runnable) = tasks.containsKey(task)
    fun runNext() { val task = tasks.keys.first(); tasks.remove(task); task.run() }
}

class GestureBarSchedulingTest {
    @Test fun deadlinesReplaceBothEarlierAndLaterAndCancelPerDisplay() {
        val state = GestureBarState()
        val scheduler = TestGestureScheduler()
        val fired = mutableListOf<Int>()
        val deadlines = GestureBarDeadlines(state, { error("Android handler not needed") }, scheduler) { fired += it }
        state.onAttachment(0, true, 1000)
        deadlines.scheduleDeadline(0)
        val original = scheduler.tasks.keys.single()
        deadlines.scheduleDeadline(0)
        assertSame(original, scheduler.tasks.keys.single())
        state.onAttachment(0, false, 1100)
        deadlines.scheduleDeadline(0)
        assertFalse(scheduler.tasks.containsKey(original))
        val hidden = scheduler.tasks.keys.single()
        state.onOverview(0, false, 1200)
        deadlines.scheduleDeadline(0)
        assertFalse(scheduler.tasks.containsKey(hidden))
        state.onAttachment(1, true, 1000)
        deadlines.scheduleDeadline(1)
        assertEquals(2, scheduler.tasks.size)
        deadlines.cancelDeadline(1)
        scheduler.runNext()
        assertEquals(listOf(0), fired)
        deadlines.scheduleDeadline(0)
        deadlines.cancelAllDeadlines()
        assertTrue(scheduler.tasks.isEmpty())
        deadlines.scheduleDeadline(9)
        assertTrue(scheduler.tasks.isEmpty())
    }
    @Test fun overdueDeadlinesArePostedWithoutNegativeDelay() {
        val state = GestureBarState()
        state.onAttachment(0, true, 1)
        val scheduler = TestGestureScheduler().apply { time = 100000 }
        GestureBarDeadlines(state, { error("unused") }, scheduler) {}.scheduleDeadline(0)
        assertEquals(0L, scheduler.tasks.values.single())
    }
    @Test fun homeProbeCoalescesRepaintsOnChangeAndRetiresPendingWork() {
        val scheduler = TestGestureScheduler()
        var active = true
        var overview = false
        var evidence = LauncherHomeProbe(true, false)
        var repaints = 0
        val probe = GestureBarHomeProbe({ null }, { error("unused") }, { true }, { active }, { overview }, { repaints++ }, scheduler, { evidence })
        repeat(4) { probe.refreshLauncherHome("test") }
        assertEquals(1, scheduler.tasks.size)
        scheduler.runNext()
        assertTrue(probe.launcherHomeVisible)
        assertEquals(1000L, probe.launcherHomeProbedAt)
        assertEquals(1, repaints)
        evidence = LauncherHomeProbe(null, false)
        probe.refreshLauncherHome("unavailable"); scheduler.runNext()
        assertTrue(probe.launcherHomeVisible)
        assertEquals(1, repaints)
        overview = true
        probe.refreshLauncherHome("overview"); scheduler.runNext()
        assertFalse(probe.launcherHomeVisible)
        assertEquals(2, repaints)
        probe.refreshLauncherHome("retire")
        probe.retire()
        assertTrue(scheduler.tasks.isEmpty())
        assertEquals(0L, probe.launcherHomeProbedAt)
        active = false
        probe.refreshLauncherHome("inactive")
        assertTrue(scheduler.tasks.isEmpty())
    }
    @Test fun rejectedProbeSchedulingCanRetry() {
        val scheduler = TestGestureScheduler().apply { accepting = false }
        val probe = GestureBarHomeProbe({ null }, { error("unused") }, { true }, { true }, { false }, {}, scheduler, { LauncherHomeProbe(true, true) })
        probe.refreshLauncherHome("rejected")
        scheduler.accepting = true
        probe.refreshLauncherHome("retry")
        assertEquals(1, scheduler.tasks.size)
        scheduler.runNext()
        assertFalse(probe.launcherHomeVisible)
    }
}
