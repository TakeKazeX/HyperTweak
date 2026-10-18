package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandSlotLeases
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout

import org.junit.Assert.*
import org.junit.Test

class ThreeIslandPolicyTest {
    @Test fun `large centre leaves both small islands clear of asymmetric status items`() {
        val layout = ThreeIslandLayout.calculate(1200, 2608, 170, 220, 700, 240, 100, 12)!!
        assertTrue(layout.leftX >= 220)
        assertTrue(layout.rightX + 100 <= 980)
        assertEquals(1200, layout.bigX * 2 + layout.bigWidth)
        assertEquals(12, layout.bigX - (layout.leftX + 100))
        assertEquals(12, layout.rightX - (layout.bigX + layout.bigWidth))
    }
    @Test fun `narrow space and large status items keep native layout`() {
        assertNull(ThreeIslandLayout.calculate(360, 800, 90, 100, 200, 180, 40, 8))
        assertNotNull(ThreeIslandLayout.calculate(2608, 1200, 170, 220, 700, 240, 100, 12))
        assertNull(ThreeIslandLayout.calculate(1200, 2608, Int.MAX_VALUE, 220, 700, 240, 100, 12))
    }
    @Test fun `ending native task refills from visible third before fourth without duplication`() {
        val slot = IslandSlotLeases<String>().apply { stage(listOf("third")) }
        val queue = mutableListOf("fourth", "fifth")
        slot.lend(queue)
        assertEquals("third", queue.removeAt(0)) // Native host fills the ended primary task.
        slot.take(queue, listOf(queue.first()))
        assertEquals("fourth", slot.tasks.firstOrNull())
        assertEquals(listOf("fifth"), queue)
        slot.lend(queue)
        slot.lend(queue) // Nested/no-op cleanup cannot insert the third task twice.
        assertEquals(listOf("fourth", "fifth"), queue)
    }
    @Test fun `touch exchange keeps positions and survives cancellation and later host deletion`() {
        val slot = IslandSlotLeases<String>().apply { stage(listOf("third")) }
        assertEquals("third", slot.exchange("third", "second"))
        assertEquals("second", slot.tasks.firstOrNull())
        val queue = mutableListOf("fourth")
        slot.lend(queue) // ACTION_CANCEL followed by an ordinary native update.
        slot.take(queue, listOf(queue.first()))
        assertEquals("second", slot.tasks.firstOrNull())
        slot.lend(queue)
        queue.remove("second") // Deletion while lent back.
        slot.take(queue, listOf(queue.first()))
        assertEquals("fourth", slot.tasks.firstOrNull())
        assertTrue(queue.isEmpty())
    }
    @Test fun `temporary expansion and feature retirement return all tasks in original order`() {
        val slot = IslandSlotLeases<String>().apply { stage(listOf("third")) }
        val queue = mutableListOf("fourth")
        slot.lend(queue)
        assertNull(slot.tasks.firstOrNull())
        assertEquals(listOf("third", "fourth"), queue)
        slot.take(queue, listOf("third")) // Native expansion ends; next eligible transaction admits it.
        slot.lend(queue)
        assertEquals(listOf("third", "fourth"), queue)
    }
}
