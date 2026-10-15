package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.*
import org.junit.Test

class SummaryIslandSchedulerTest {
    private fun SummaryIslandScheduler.selected(visible: List<String>, queued: List<String>): List<String> =
        select(visible, queued, (visible + queued).toSet()).visible

    @Test fun `third arriving task automatically fills capacity after two tasks were displayed`() {
        val scheduler = SummaryIslandScheduler()
        assertEquals(listOf("a", "b"), scheduler.selected(listOf("a", "b"), emptyList()))
        val selected = scheduler.select(listOf("a", "b"), listOf("new"), setOf("a", "b", "new"))
        assertEquals(listOf("a", "b"), selected.native)
        assertEquals("new", selected.extra)
    }

    @Test fun `new task fills third seat after a manual hide without restoring the hidden task`() {
        val scheduler = SummaryIslandScheduler()
        scheduler.commit(scheduler.plan(listOf("a", "b", "hidden"), emptyList(), false, "hidden")!!)
        assertEquals(listOf("a", "b"), scheduler.selected(listOf("a", "b"), listOf("hidden")))
        assertEquals(listOf("a", "b", "new"), scheduler.selected(listOf("a", "b"), listOf("hidden", "new")))
        assertTrue(scheduler.isHidden("hidden"))
    }

    @Test fun `new task promoted into a native handler still leaves the third seat filled`() {
        val scheduler = SummaryIslandScheduler()
        scheduler.commit(scheduler.plan(listOf("a", "b", "hidden"), emptyList(), false, "hidden")!!)
        val selected = scheduler.select(listOf("new", "a"), listOf("hidden", "b"), setOf("new", "a", "b", "hidden"))
        assertEquals(listOf("new", "a"), selected.native)
        assertEquals("b", selected.extra)
        assertTrue(scheduler.isHidden("hidden"))
    }

    @Test fun `new tasks appear after every previous task was manually hidden`() {
        val scheduler = SummaryIslandScheduler()
        listOf("a", "b", "c").forEach { task ->
            scheduler.commit(scheduler.plan(listOf(task), emptyList(), false, task)!!)
        }
        assertTrue(scheduler.selected(emptyList(), listOf("a", "b", "c")).isEmpty())
        assertEquals(listOf("new"), scheduler.selected(emptyList(), listOf("a", "b", "c", "new")))
    }

    @Test fun `third selection skips incompatible templates and never duplicates native slots`() {
        val scheduler = SummaryIslandScheduler()
        val selected = scheduler.select(listOf("a", "b"), listOf("a", "b", "no-small", "new", "backup"), setOf("a", "b", "new", "backup"))
        assertEquals(listOf("a", "b", "new"), selected.visible)
    }

    @Test fun `task deletion reduces actual count without preventing the next task from entering`() {
        val scheduler = SummaryIslandScheduler()
        assertEquals(listOf("a", "b", "c"), scheduler.selected(listOf("a", "b"), listOf("c")))
        assertEquals(listOf("a", "b"), scheduler.selected(listOf("a", "b"), emptyList()))
        assertEquals(listOf("a", "b", "d"), scheduler.selected(listOf("a", "b"), listOf("d")))
    }

    @Test fun `opening a card admits a rotation-hidden fourth task already promoted by the host`() {
        val policy = SummaryIslandScheduler()
        val rotated = policy.plan(listOf("a", "b", "c"), listOf("d"), true, "a")!!
        policy.commit(rotated)
        assertTrue(policy.isHidden("a"))
        assertEquals("a", policy.fillExpandedVacancy(null, "d", rotated.visible, listOf("b", "a", "c")))
        assertFalse(policy.isHidden("a"))
        assertEquals(setOf("a", "b", "c"), policy.selected(listOf("b", "a"), listOf("c")).toSet())
    }

    @Test fun `opening a card also admits a fourth task still waiting in the hidden queue`() {
        val policy = SummaryIslandScheduler()
        val rotated = policy.plan(listOf("a", "b", "c"), listOf("d"), true, "a")!!
        policy.commit(rotated)
        assertEquals("a", policy.fillExpandedVacancy(null, "d", rotated.visible, listOf("b", "c", "a")))
        assertEquals(listOf("b", "c", "a"), policy.selected(listOf("b", "c"), listOf("a")))
    }

    @Test fun `expansion releases only one backup and leaves other manual hides intact`() {
        val policy = SummaryIslandScheduler()
        policy.commit(policy.plan(listOf("a", "b", "c"), emptyList(), false, "a")!!)
        policy.commit(policy.plan(listOf("d", "b", "c"), emptyList(), false, "d")!!)
        assertEquals("d", policy.fillExpandedVacancy(null, "b", listOf("b", "c"), listOf("c", "d", "a")))
        assertFalse(policy.isHidden("d"))
        assertTrue(policy.isHidden("a"))
        assertEquals(listOf("c", "d"), policy.selected(listOf("c"), listOf("d", "a")))
    }

    @Test fun `an unchanged expanded card or collapse cannot undo a manual hide`() {
        val policy = SummaryIslandScheduler()
        policy.commit(policy.plan(listOf("a", "b", "c"), emptyList(), false, "a")!!)
        assertNull(policy.fillExpandedVacancy("card", "card", listOf("b", "c"), listOf("b", "c", "a")))
        assertNull(policy.fillExpandedVacancy("card", null, listOf("b", "c"), listOf("b", "c", "a")))
        assertNull(policy.fillExpandedVacancy(null, "external", listOf("b", "c"), listOf("b", "c", "a")))
        assertTrue(policy.isHidden("a"))
    }

    @Test fun `replacing the expanded card refills the new vacancy`() {
        val policy = SummaryIslandScheduler()
        val rotated = policy.plan(listOf("a", "b", "c"), listOf("d"), true, "a")!!
        policy.commit(rotated)
        assertEquals("a", policy.fillExpandedVacancy("previous-card", "d", rotated.visible, listOf("b", "c", "a")))
        assertFalse(policy.isHidden("a"))
    }

    @Test fun `opening one of only three tasks never duplicates the expanded task into a summary`() {
        val policy = SummaryIslandScheduler()
        assertNull(policy.fillExpandedVacancy(null, "a", listOf("a", "b", "c"), listOf("b", "c", "a")))
        assertEquals(listOf("b", "c"), policy.selected(listOf("b", "c"), emptyList()))
    }

    @Test fun `fourth task fills the exact seat vacated by a hide`() {
        val policy = SummaryIslandScheduler()
        val before = listOf("media", "timer", "delivery")
        val change = policy.plan(before, listOf("fourth"), false, "timer")!!
        policy.commit(change)
        assertEquals(listOf("media", "fourth", "delivery"), change.visible)
        assertTrue(policy.isHidden("timer"))
        assertEquals(listOf("media", "fourth", "delivery"),
            policy.selected(change.visible, listOf("timer")))
        // Once every waiting task has had a turn, another hide can reduce the count.
        val next = policy.plan(change.visible, listOf("timer"), false, "fourth")!!
        assertEquals(listOf("media", "delivery"), next.visible)
    }

    @Test fun `an unseen fourth task takes priority over restoring a recently hidden task`() {
        val policy = SummaryIslandScheduler()
        policy.commit(policy.plan(listOf("a", "b", "c"), emptyList(), false, "a")!!)
        val change = policy.plan(listOf("b", "c"), listOf("a", "fourth"), true, null)!!
        assertEquals(listOf("b", "c", "fourth"), change.visible)
        assertEquals("fourth", change.task)
    }

    @Test fun `full summary seats still rotate one queued task without disturbing neighbours`() {
        val policy = SummaryIslandScheduler()
        var visible = listOf("a", "b", "c")
        val queued = mutableListOf("d", "e", "f")
        val seen = visible.toMutableSet()
        repeat(9) {
            val old = visible
            val change = policy.plan(visible, queued, true, visible[1])!!
            policy.commit(change)
            visible = change.visible
            queued.remove(change.task)
            queued.add(change.displaced!!)
            seen.addAll(visible)
            assertEquals(3, visible.size)
            assertEquals(old[0], visible[0])
            assertEquals(old[2], visible[2])
            assertTrue(policy.isHidden(change.displaced))
            assertFalse(policy.isHidden(change.task))
            assertEquals(6, (visible + queued).toSet().size)
        }
        assertEquals(setOf("a", "b", "c", "d", "e", "f"), seen)
    }

    @Test fun `stale full-capacity gesture cannot evict a replacement occupant`() {
        val policy = SummaryIslandScheduler()
        assertNull(policy.plan(listOf("a", "b", "c"), listOf("d"), true, "removed"))
        assertFalse(policy.hasHidden)
    }

    @Test fun `three hides and three reveals each change exactly one task`() {
        val policy = SummaryIslandScheduler()
        var visible = listOf("media", "timer", "delivery")
        val queued = mutableListOf<String>()
        repeat(3) { step ->
            val change = policy.plan(visible, queued, false, null)!!
            policy.commit(change)
            visible = change.visible
            queued.add(change.task)
            visible = policy.selected(visible, queued)
            assertEquals(2 - step, visible.size)
            assertFalse(change.task in visible)
        }
        repeat(3) { step ->
            val change = policy.plan(visible, queued, true, null)!!
            policy.commit(change)
            visible = change.visible
            queued.remove(change.task)
            visible = policy.selected(visible, queued)
            assertEquals(step + 1, visible.size)
        }
        assertEquals(listOf("media", "timer", "delivery"), visible)
        assertTrue(queued.isEmpty())
    }

    @Test fun `second reveal cannot drain the native queue`() {
        val policy = SummaryIslandScheduler()
        val first = policy.plan(emptyList(), listOf("a", "b", "c", "d"), true, null)!!
        policy.commit(first)
        val second = policy.plan(first.visible, listOf("b", "c", "d"), true, null)!!
        policy.commit(second)
        assertEquals(listOf("a", "b"), second.visible)
        assertEquals(listOf("a", "b", "c"), policy.selected(second.visible, listOf("c", "d")))
    }

    @Test fun `hide the touched side without moving other tasks to the hidden queue`() {
        val policy = SummaryIslandScheduler()
        val change = policy.plan(listOf("big", "left", "right"), emptyList(), false, "left")!!
        policy.commit(change)
        assertEquals(listOf("big", "right"), change.visible)
        assertTrue(policy.isHidden("left"))
        assertFalse(policy.isHidden("right"))
        assertEquals(listOf("big", "right", "backup"), policy.selected(change.visible, listOf("left", "backup")))
    }

    @Test fun `host update cannot turn a manual hide into a small island`() {
        val policy = SummaryIslandScheduler()
        policy.commit(policy.plan(listOf("a", "b", "c"), emptyList(), false, "a")!!)
        assertEquals(listOf("b", "c", "d"), policy.selected(listOf("a", "b"), listOf("c", "d")))
    }

    @Test fun `preview and cancellation do not alter the committed queue policy`() {
        val policy = SummaryIslandScheduler()
        repeat(20) { policy.plan(listOf("a", "b", "c"), listOf("d"), false, "b") }
        assertFalse(policy.isHidden("b"))
        assertEquals(listOf("a", "b", "c"), policy.selected(listOf("a", "b", "c"), listOf("d")))
    }

    @Test fun `removed DOWN target cannot hide its replacement`() {
        val policy = SummaryIslandScheduler()
        assertNull(policy.plan(listOf("a", "replacement"), listOf("d"), false, "old"))
    }

    @Test fun `empty endpoints do not rotate other tasks`() {
        val policy = SummaryIslandScheduler()
        assertNull(policy.plan(emptyList(), listOf("a", "b"), false, null))
        assertNull(policy.plan(listOf("a"), emptyList(), true, "a"))
    }

    @Test fun `disable and removed tasks release manual visibility memory`() {
        val policy = SummaryIslandScheduler()
        policy.commit(policy.plan(listOf("a", "b"), emptyList(), false, "a")!!)
        policy.retain(setOf("b"))
        assertFalse(policy.hasHidden)
        policy.commit(policy.plan(listOf("b"), emptyList(), false, "b")!!)
        policy.clear()
        assertFalse(policy.hasHidden)
    }
}
