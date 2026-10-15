package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.*
import org.junit.Test

class ThreeIslandAdaptiveLayoutTest {
    @Test fun `two occupied full islands keep their different natural content widths`() {
        val sizes = listOf(IslandContentSize(110, 230), IslandContentSize(70, 140))
        val row = FullIslandRow.calculate(2608, 1200, 170, 220, sizes, 12)!!
        assertEquals(2, row.size)
        assertEquals(listOf(340, 210), row.map { it.width })
        assertEquals(12, row[1].x - row[0].x - row[0].width)
        assertEquals(2608, row.first().x + row.last().x + row.last().width)
    }

    @Test fun `expanding one task removes its slot instead of reserving a phantom third island`() {
        val sizes = listOf(IslandContentSize(110, 230), IslandContentSize(70, 140), IslandContentSize(85, 200))
        val before = FullIslandRow.calculate(2608, 1200, 170, 220, sizes, 12)!!
        for (removed in sizes.indices) {
            val row = FullIslandRow.calculate(2608, 1200, 170, 220, sizes.filterIndexed { i, _ -> i != removed }, 12)!!
            assertEquals(2, row.size)
            assertEquals(sizes.filterIndexed { i, _ -> i != removed }, row.map { it.content })
            assertEquals(12, row[1].x - row[0].x - row[0].width)
            assertTrue(row.last().x + row.last().width - row.first().x < before.last().x + before.last().width - before.first().x)
        }
    }

    @Test fun `single summary does not inherit missing side seats`() {
        val row = FullIslandRow.calculate(2608, 1200, 170, 220, listOf(IslandContentSize(100, 200)), 12)!!
        assertEquals(1, row.size)
        assertEquals(300, row.single().width)
        assertEquals(1154, row.single().x)
    }

    @Test fun `natural measurement removes mirrored halves and cutout gap but retains pill height`() {
        assertEquals(IslandContentSize(70, 210), IslandContentSize.measured(70, 210, 80))
        val short = IslandContentSize.measured(10, 20, 80)!!
        assertEquals(80, short.width)
        assertEquals(10, short.right - short.left)
        assertNull(IslandContentSize.measured(-1, 10, 80))
        assertNull(IslandContentSize.measured(Int.MAX_VALUE, 20, 80))
    }

    @Test fun `insufficient full width falls back without truncating templates`() {
        assertNull(FullIslandRow.calculate(900, 400, 100, 100,
            listOf(IslandContentSize(150, 300), IslandContentSize(180, 300)), 12))
        assertNotNull(ThreeIslandLayout.calculate(900, 400, 100, 100, 300, 240, 80, 12))
        assertNull(ThreeIslandLayout.calculate(600, 1400, 100, 100, 300, 240, 80, 12))
    }

    @Test fun `landscape safe insets and status items bound the complete row`() {
        val row = FullIslandRow.calculate(2608, 1200, 80, 120,
            listOf(IslandContentSize(200, 300), IslandContentSize(70, 240)), 12, 350, 0)!!
        assertTrue(row.first().x >= 350)
        assertTrue(row.last().x + row.last().width <= 2608 - 120)
        assertNull(FullIslandRow.calculate(1200, 2608, -1, 100, listOf(IslandContentSize(70, 240)), 12))
    }

    @Test fun `occupied rows remain contiguous and centred across sizes and task counts`() {
        for (width in 320..3000 step 37) for (count in 1..10) for (edge in listOf(0, 80, 220)) {
            val sizes = List(count) { IslandContentSize(50 + 30 * it, 90 + 40 * it) }
            val row = FullIslandRow.calculate(width, 1200, edge, edge / 2, sizes, 13) ?: continue
            assertEquals(sizes, row.map { it.content })
            assertTrue(row.first().x >= edge)
            assertTrue(row.last().x.toLong() + row.last().width <= width.toLong() - edge / 2)
            row.zipWithNext().forEach { (left, right) -> assertEquals(13, right.x - left.x - left.width) }
            assertTrue(kotlin.math.abs(width - row.first().x - row.last().x - row.last().width) <= 1)
        }
    }
}
