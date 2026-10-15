package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.*
import org.junit.Test

class ManyIslandPolicyTest {
    @Test fun `enabling more islands releases previous rotation exclusions but preserves new swipes`() {
        val scheduler = SummaryIslandScheduler()
        val visible = listOf("timer", "stopwatch", "recorder")
        scheduler.commit(scheduler.plan(visible, listOf("music"), true, "recorder")!!)
        assertTrue(scheduler.isHidden("recorder"))
        scheduler.capacityChanged(false, true)
        assertFalse(scheduler.isHidden("recorder"))
        val all = visible + "music"
        assertEquals(all, scheduler.select(visible, listOf("music"), all.toSet(), Int.MAX_VALUE).visible)
        scheduler.commit(scheduler.plan(all, emptyList(), false, "music", 4)!!)
        scheduler.capacityChanged(true, true)
        assertTrue(scheduler.isHidden("music")) // Ordinary events must respect a new user dismissal.
        scheduler.capacityChanged(true, false)
        scheduler.capacityChanged(false, true)
        assertFalse(scheduler.isHidden("music"))
    }

    @Test fun `multiple leases preserve host queue and exchange any clicked island`() {
        val leases = IslandSlotLeases<String>()
        val queue = mutableListOf("c", "d", "e", "f")
        leases.take(queue, listOf("c", "d", "e"))
        assertEquals(listOf("f"), queue)
        assertEquals("e", leases.exchange("e", "b"))
        leases.lend(queue)
        leases.lend(queue)
        assertEquals(listOf("c", "d", "b", "f"), queue)
        assertTrue(leases.tasks.isEmpty())
        queue.remove("d") // Native deletion while all leases are returned.
        leases.take(queue, listOf("c", "b"))
        leases.lend(queue)
        assertEquals(listOf("c", "b", "f"), queue)
    }

    @Test fun `stale admission is atomic and does not lose queued tasks`() {
        val leases = IslandSlotLeases<String>()
        val queue = mutableListOf("a", "b")
        assertThrows(IllegalStateException::class.java) { leases.take(queue, listOf("a", "gone")) }
        assertEquals(listOf("a", "b"), queue)
        assertTrue(leases.tasks.isEmpty())
    }

    @Test fun `admission skips oversized task and fills screen without a fixed island cap`() {
        val sizes = mapOf("a" to 200, "b" to 180, "wide" to 1200, "c" to 150, "d" to 100, "e" to 160, "f" to 400)
        val all = listOf("a", "b", "wide", "c", "d", "e", "f")
        val admitted = FullIslandRow.admit(all.take(2), all.drop(2)) { items ->
            FullIslandRow.calculate(1200, 600, 100, 100,
                items.map { IslandContentSize(sizes.getValue(it), 0) }, 10) != null
        }
        assertEquals(listOf("a", "b", "c", "d", "e"), admitted)
        val row = FullIslandRow.calculate(1200, 600, 100, 100,
            admitted.map { IslandContentSize(sizes.getValue(it), 0) }, 10)!!
        assertEquals(5, row.size)
        assertTrue(row.first().x >= 100)
        assertTrue(row.last().x + row.last().width <= 1100)
    }

    @Test fun `many island scheduling respects hidden tasks and dynamic gesture capacity`() {
        val scheduler = SummaryIslandScheduler()
        val visible = listOf("a", "b", "c", "d", "e")
        val hidden = scheduler.plan(visible, emptyList(), false, "d", 5)!!
        scheduler.commit(hidden)
        val pool = visible + listOf("f", "g")
        assertEquals(listOf("a", "b", "c", "e", "f", "g"),
            scheduler.select(hidden.visible, listOf("d", "f", "g"), pool.toSet(), Int.MAX_VALUE).visible)
        val replace = scheduler.plan(hidden.visible, listOf("d", "f"), true, "e", 4)!!
        assertEquals(listOf("a", "b", "c", "f"), replace.visible)
        assertEquals("e", replace.displaced)
        val append = scheduler.plan(hidden.visible, listOf("d", "f"), true, "e", 5)!!
        assertEquals(listOf("a", "b", "c", "e", "f"), append.visible)
        assertNull(append.displaced)
    }
}
