package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.*
import org.junit.Test

class SummaryIslandSwipeTest {
    @Test fun `opening a card admits a rotation-hidden fourth task already promoted by the host`() {
        val policy = SummaryIslandSwipe()
        val rotated = policy.plan(listOf("a", "b", "c"), listOf("d"), true, "a")!!
        policy.commit(rotated)
        assertTrue(policy.isHidden("a"))
        assertEquals("a", policy.fillExpandedVacancy(null, "d", rotated.visible, listOf("b", "a", "c")))
        assertFalse(policy.isHidden("a"))
        assertEquals(setOf("a", "b", "c"), policy.reconcile(listOf("b", "a"), listOf("c"), 3).toSet())
    }

    @Test fun `opening a card also admits a fourth task still waiting in the hidden queue`() {
        val policy = SummaryIslandSwipe()
        val rotated = policy.plan(listOf("a", "b", "c"), listOf("d"), true, "a")!!
        policy.commit(rotated)
        assertEquals("a", policy.fillExpandedVacancy(null, "d", rotated.visible, listOf("b", "c", "a")))
        assertEquals(listOf("b", "c", "a"), policy.reconcile(listOf("b", "c"), listOf("a"), 3))
    }

    @Test fun `expansion releases only one backup and leaves other manual hides intact`() {
        val policy = SummaryIslandSwipe()
        policy.commit(policy.plan(listOf("a", "b", "c"), emptyList(), false, "a")!!)
        policy.commit(policy.plan(listOf("d", "b", "c"), emptyList(), false, "d")!!)
        assertEquals("d", policy.fillExpandedVacancy(null, "b", listOf("b", "c"), listOf("c", "d", "a")))
        assertFalse(policy.isHidden("d"))
        assertTrue(policy.isHidden("a"))
        assertEquals(listOf("c", "d"), policy.reconcile(listOf("c"), listOf("d", "a"), 2))
    }

    @Test fun `an unchanged expanded card or collapse cannot undo a manual hide`() {
        val policy = SummaryIslandSwipe()
        policy.commit(policy.plan(listOf("a", "b", "c"), emptyList(), false, "a")!!)
        assertNull(policy.fillExpandedVacancy("card", "card", listOf("b", "c"), listOf("b", "c", "a")))
        assertNull(policy.fillExpandedVacancy("card", null, listOf("b", "c"), listOf("b", "c", "a")))
        assertNull(policy.fillExpandedVacancy(null, "external", listOf("b", "c"), listOf("b", "c", "a")))
        assertTrue(policy.isHidden("a"))
    }

    @Test fun `replacing the expanded card refills the new vacancy`() {
        val policy = SummaryIslandSwipe()
        val rotated = policy.plan(listOf("a", "b", "c"), listOf("d"), true, "a")!!
        policy.commit(rotated)
        assertEquals("a", policy.fillExpandedVacancy("previous-card", "d", rotated.visible, listOf("b", "c", "a")))
        assertFalse(policy.isHidden("a"))
    }

    @Test fun `opening one of only three tasks never duplicates the expanded task into a summary`() {
        val policy = SummaryIslandSwipe()
        assertNull(policy.fillExpandedVacancy(null, "a", listOf("a", "b", "c"), listOf("b", "c", "a")))
        assertEquals(listOf("b", "c"), policy.reconcile(listOf("b", "c"), emptyList(), 3))
    }

    @Test fun `fourth task fills the exact seat vacated by a hide`() {
        val policy = SummaryIslandSwipe()
        val before = listOf("media", "timer", "delivery")
        val change = policy.plan(before, listOf("fourth"), false, "timer")!!
        policy.commit(change)
        assertEquals(listOf("media", "fourth", "delivery"), change.visible)
        assertTrue(policy.isHidden("timer"))
        assertEquals(listOf("media", "fourth", "delivery"),
            policy.reconcile(change.visible, listOf("timer"), change.visible.size))
        // Once every waiting task has had a turn, another hide can reduce the count.
        val next = policy.plan(change.visible, listOf("timer"), false, "fourth")!!
        assertEquals(listOf("media", "delivery"), next.visible)
    }

    @Test fun `an unseen fourth task takes priority over restoring a recently hidden task`() {
        val policy = SummaryIslandSwipe()
        policy.commit(policy.plan(listOf("a", "b", "c"), emptyList(), false, "a")!!)
        val change = policy.plan(listOf("b", "c"), listOf("a", "fourth"), true, null)!!
        assertEquals(listOf("b", "c", "fourth"), change.visible)
        assertEquals("fourth", change.task)
    }

    @Test fun `full summary seats still rotate one queued task without disturbing neighbours`() {
        val policy = SummaryIslandSwipe()
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
        val policy = SummaryIslandSwipe()
        assertNull(policy.plan(listOf("a", "b", "c"), listOf("d"), true, "removed"))
        assertFalse(policy.hasHidden)
    }

    @Test fun `three hides and three reveals each change exactly one task`() {
        val policy = SummaryIslandSwipe()
        var visible = listOf("media", "timer", "delivery")
        val queued = mutableListOf<String>()
        repeat(3) { step ->
            val change = policy.plan(visible, queued, false, null)!!
            policy.commit(change)
            visible = change.visible
            queued.add(change.task)
            assertEquals(2 - step, visible.size)
            assertFalse(change.task in visible)
        }
        repeat(3) { step ->
            val change = policy.plan(visible, queued, true, null)!!
            policy.commit(change)
            visible = change.visible
            queued.remove(change.task)
            assertEquals(step + 1, visible.size)
        }
        assertEquals(listOf("media", "timer", "delivery"), visible)
        assertTrue(queued.isEmpty())
    }

    @Test fun `second reveal cannot drain the native queue`() {
        val policy = SummaryIslandSwipe()
        val first = policy.plan(emptyList(), listOf("a", "b", "c", "d"), true, null)!!
        policy.commit(first)
        val second = policy.plan(first.visible, listOf("b", "c", "d"), true, null)!!
        policy.commit(second)
        assertEquals(listOf("a", "b"), second.visible)
        assertEquals(listOf("a", "b"), policy.reconcile(second.visible, listOf("c", "d"), second.visible.size))
    }

    @Test fun `hide the touched side without moving other tasks to the hidden queue`() {
        val policy = SummaryIslandSwipe()
        val change = policy.plan(listOf("big", "left", "right"), emptyList(), false, "left")!!
        policy.commit(change)
        assertEquals(listOf("big", "right"), change.visible)
        assertTrue(policy.isHidden("left"))
        assertFalse(policy.isHidden("right"))
        assertEquals(listOf("big", "right"), policy.reconcile(change.visible, listOf("left", "backup"), 2))
    }

    @Test fun `host update cannot turn a manual hide into a small island`() {
        val policy = SummaryIslandSwipe()
        policy.commit(policy.plan(listOf("a", "b", "c"), emptyList(), false, "a")!!)
        assertEquals(listOf("b", "c"), policy.reconcile(listOf("a", "b"), listOf("c", "d"), 2))
        assertEquals(emptyList<String>(), policy.reconcile(listOf("a", "b"), listOf("c", "d"), 0))
    }

    @Test fun `preview and cancellation do not alter the committed queue policy`() {
        val policy = SummaryIslandSwipe()
        repeat(20) { policy.plan(listOf("a", "b", "c"), listOf("d"), false, "b") }
        assertFalse(policy.isHidden("b"))
        assertEquals(listOf("a", "b", "c"), policy.reconcile(listOf("a", "b", "c"), listOf("d"), 3))
    }

    @Test fun `removed DOWN target cannot hide its replacement`() {
        val policy = SummaryIslandSwipe()
        assertNull(policy.plan(listOf("a", "replacement"), listOf("d"), false, "old"))
    }

    @Test fun `empty endpoints do not rotate other tasks`() {
        val policy = SummaryIslandSwipe()
        assertNull(policy.plan(emptyList(), listOf("a", "b"), false, null))
        assertNull(policy.plan(listOf("a"), emptyList(), true, "a"))
    }

    @Test fun `disable and removed tasks release manual visibility memory`() {
        val policy = SummaryIslandSwipe()
        policy.commit(policy.plan(listOf("a", "b"), emptyList(), false, "a")!!)
        policy.retain(setOf("b"))
        assertFalse(policy.hasHidden)
        policy.commit(policy.plan(listOf("b"), emptyList(), false, "b")!!)
        policy.clear()
        assertFalse(policy.hasHidden)
    }
}
