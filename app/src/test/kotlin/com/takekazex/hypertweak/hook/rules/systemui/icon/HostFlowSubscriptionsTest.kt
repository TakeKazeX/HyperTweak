package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.*
import org.junit.Test

class HostFlowSubscriptionsTest {
    class Job {
        var cancellations = 0
        fun cancel() { cancellations++ }
    }

    @Test fun rebindingDropsOldQueuedReplayAndEmissionsWithinTheSameGeneration() {
        val queued = ArrayList<() -> Unit>()
        val consumers = ArrayList<(Any?) -> Unit>()
        val jobs = ArrayList<Job>()
        val output = ArrayList<Any?>()
        var generationActive = true
        val subscriptions = HostFlowSubscriptions(
            post = { queued += it }, initialValue = { it },
            collectHost = { _, _, consumer, _ ->
                consumers += consumer
                Job().also(jobs::add).let(HostFlowCollector::Handle)
            }
        )
        assertTrue(subscriptions.collect(Unit, "old", { generationActive }, replay = true) { output += it })
        subscriptions.clear()
        assertTrue(subscriptions.collect(Unit, "new", { generationActive }, replay = true) { output += it })
        queued.forEach { it() }
        consumers[0]("late old event")
        consumers[1]("new event")
        assertEquals(listOf("new", "new event"), output)
        generationActive = false
        consumers[1]("retired generation")
        assertEquals(2, output.size)
        subscriptions.clear()
        subscriptions.clear()
        assertTrue(jobs.all { it.cancellations == 1 })
        assertFalse(subscriptions.isBound)
    }

    @Test fun partialRequiredBindingRollsBackOnlyTheAcquiredSubscriptions() {
        val job = Job()
        val acquired = ArrayList<HostFlowCollector.Handle>()
        val released = ArrayList<HostFlowCollector.Handle>()
        val subscriptions = HostFlowSubscriptions(
            acquired = acquired::add,
            released = { released.addAll(it) },
            collectHost = { _, flow, _, _ -> if (flow == "missing") null else HostFlowCollector.Handle(job) }
        )
        assertTrue(subscriptions.collect(Unit, "present", { true }) {})
        assertFalse(subscriptions.collect(Unit, "missing", { true }) {})
        subscriptions.clear()
        assertEquals(1, job.cancellations)
        assertEquals(acquired, released)
        assertTrue(subscriptions.snapshot().isEmpty())
    }
    @Test fun customVisibilityCollectorCannotReviveAfterItsBindingIsCleared() {
        val subscriptions = HostFlowSubscriptions()
        val job = Job()
        var deliver: (Any?) -> Unit = {}
        var current: () -> Boolean = { false }
        val values = ArrayList<Any?>()
        assertTrue(subscriptions.collectUsing(Unit, { true }, start = { consumer, gate ->
            deliver = consumer
            current = gate
            HostFlowCollector.Handle(job)
        }) { values += it })
        deliver("live")
        assertTrue(current())
        subscriptions.clear()
        assertFalse(current())
        deliver("retired")
        assertEquals(listOf("live"), values)
        assertEquals(1, job.cancellations)
    }

}
