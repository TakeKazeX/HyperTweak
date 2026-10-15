package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.*
import org.junit.Test

class PortraitIslandLayoutTest {
    @Test fun `short icon side loses mirrored blank width but camera remains at physical centre`() {
        val content = IslandContentSize(93, 216)
        val row = PortraitIslandLayout.calculate(1200, 2608, 170, 170, content, 72, 100, 12, true, true)!!
        assertEquals(381, row.bigWidth)
        assertEquals(600, row.bigX + content.left + 36)
        assertEquals(123, 216 * 2 + 72 - row.bigWidth)
        assertTrue(row.leftX >= 170)
        assertTrue(row.rightX + 100 <= 1030)
        assertEquals(12, row.bigX - row.leftX - 100)
        assertEquals(12, row.rightX - row.bigX - row.bigWidth)
    }

    @Test fun `large font or status occupation fails safely rather than squeezing native contents`() {
        assertNull(PortraitIslandLayout.calculate(1200, 2608, 170, 220,
            IslandContentSize(100, 400), 72, 100, 12, true, true))
        assertNotNull(PortraitIslandLayout.calculate(1200, 2608, 170, 220,
            IslandContentSize(100, 300), 72, 100, 12, true, false))
    }

    @Test fun `camera anchored geometry survives width changes without sharing landscape centre`() {
        for (width in 900..1600 step 17) for (cutout in listOf(59, 72, 101)) {
            val content = IslandContentSize(93, 216)
            val row = PortraitIslandLayout.calculate(width, 2608, 50, 50, content, cutout, 80, 12, true, true) ?: continue
            assertEquals((width - cutout) / 2, row.bigX + content.left)
            assertEquals(content.width + cutout, row.bigWidth)
        }
    }
}
