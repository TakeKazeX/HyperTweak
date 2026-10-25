package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import org.junit.Assert.*
import org.junit.Test

class DuoPanelSettleTest {
    private class Host : DuoPanelSettle.Host {
        var task: Runnable? = null
        var live = true
        var fail = false
        val draws = mutableListOf<Float>()
        val progress = mutableListOf<Float>()
        val queued = ArrayDeque<Runnable>()
        var clears = 0; var arrivals = 0; var failures = 0
        override fun current(task: Runnable) = this.task === task
        override fun live() = live
        override fun progress(value: Float) { progress += value }
        override fun draw(value: Float) { if (fail) error("host rejected overlay"); draws += value }
        override fun clear() { clears++; task = null }
        override fun arrive() { arrivals++ }
        override fun post(task: Runnable) { queued += task }
        override fun failed(error: Throwable) { failures++ }
        fun start(from: Float) { task = DuoPanelSettle(from, this); queued += requireNotNull(task) }
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }
    @Test fun oneStepOpenTravelsSixFramesThenReleasesAndArrivesExactlyOnce() {
        val host = Host(); host.start(.2f); val task = host.task
        host.drain(); task?.run()
        assertEquals(6, host.draws.size)
        assertTrue(host.draws.zipWithNext().all { (a, b) -> b > a })
        assertEquals(1f, host.progress.last(), 0f)
        assertEquals(1, host.clears); assertEquals(1, host.arrivals)
    }
    @Test fun oldQueuedTaskCannotDrawOrClearAReplacement() {
        val host = Host(); host.start(0f); val old = requireNotNull(host.task)
        host.start(.4f); old.run(); host.drain()
        assertEquals(6, host.draws.size)
        assertTrue(host.draws.first() > .4f)
        assertEquals(1, host.clears)
    }
    @Test fun disableOrBindingRemovalClearsWithoutInventingAnArrival() {
        val host = Host(); host.start(0f)
        host.queued.removeFirst().run(); host.live = false; host.drain()
        assertEquals(1, host.draws.size)
        assertEquals(1, host.clears); assertEquals(0, host.arrivals)
    }
    @Test fun hostDrawingFailureReleasesLeasesAndStopsTheTimeline() {
        val host = Host(); host.start(.5f); host.fail = true; host.drain()
        assertEquals(1, host.failures); assertEquals(1, host.clears); assertEquals(1, host.arrivals)
        assertEquals(1f, host.progress.last(), 0f)
    }
}
