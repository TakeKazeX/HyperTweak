package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.policy.SmallIslandSeats
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.spectrum.SmallIslandSpectrum

import org.junit.Assert.*
import org.junit.Test

class SmallIslandSpectrumTest {
    @Test fun `app close destination matches paired native rendering instead of small circle`() {
        val geometry = SmallIslandSpectrum.ReturnGeometry("media",
            ThreeIslandLayout(471, 317, 357, 800, false, 1200), IslandContentSize(90, 90),
            12, 239, 218, 0, 0, 102)
        // Actual native Binder query while the music view is still AppExpanded.
        val left = geometry.target(279, 102)!!
        assertEquals(279, left.x)
        assertEquals(180, left.width)
        assertEquals(459, left.x + left.width)
        val right = geometry.target(800, 102)!!
        assertEquals(800, right.x)
        assertEquals(180, right.width)
        // Both callers derive their destination through the same spectrum admission policy.
        assertEquals(left, SmallIslandSpectrum.choose(geometry.layout, geometry.content,
            12, 239, 218, 0, 0, true, 102)!!.frame)
    }

    @Test fun `return destination retains native cover when current side cannot admit spectrum`() {
        val geometry = SmallIslandSpectrum.ReturnGeometry("media",
            ThreeIslandLayout(471, 317, 357, 800, false, 1200), IslandContentSize(90, 90),
            12, 300, 218, 0, 0, 102)
        assertNull(geometry.target(357, 102))
        assertNotNull(geometry.target(800, 102))
        assertNull(geometry.target(471, 102)) // Main/cutout targets must never become side targets.
    }

    @Test fun `two islands can admit spectrum without reserving an empty opposite seat`() {
        val row = ThreeIslandLayout(200, 600, 80, 820, false, 1200)
        val size = IslandContentSize(90, 90)
        // Left has no room for an occupied third island, but it is empty here.
        assertNotNull(SmallIslandSpectrum.choose(row, size, 20, 190, 100, 0, 0, false, 0))
        assertNull(SmallIslandSpectrum.choose(row, size, 20, 190, 100, 0, 0, false, 102))
        assertNull(SmallIslandSpectrum.choose(row, size, 20, 190, 250, 0, 0, false, 0))
        assertNull(SmallIslandSpectrum.choose(row, size, 20, 190, 100, 0, 0, true, 0))
        assertNull(SmallIslandSpectrum.choose(row, size, 20, 190, 100, 0, 0, false, -1))
        val geometry = SmallIslandSpectrum.ReturnGeometry("media", row, size, 20, 190, 100, 0, 0, 0)
        assertEquals(180, geometry.target(820, 102)!!.width)
    }

    private val layout = ThreeIslandLayout(320, 430, 250, 770, false, 1200)
    private val content = IslandContentSize(80, 100)
    @Test fun `music with big island lyric allocations fits after using its native icon modules`() {
        val device = ThreeIslandLayout(467, 316, 347, 801, false, 1200)
        assertNull(SmallIslandSpectrum.choose(device, IslandContentSize(332, 325), 18,
            229, 281, 0, 0, true, 102))
        val seat = SmallIslandSpectrum.choose(device, IslandContentSize(90, 90), 18,
            229, 281, 0, 0, true, 102)!!
        assertTrue(seat.left)
        assertEquals(269, seat.frame.x)
        assertEquals(180, seat.frame.width)
    }
    @Test fun `use natural icon demand instead of a previous main template allocation`() {
        val inflated = IslandContentSize(216, 216)
        val natural = IslandContentSize(90, 90)
        assertNull(SmallIslandSpectrum.choose(layout, inflated, 20, 100, 100, 0, 0, true, 50))
        assertNotNull(SmallIslandSpectrum.choose(layout, natural, 20, 100, 100, 0, 0, true, 50))
    }
    private fun choose(left: Boolean, clock: Int = 100, battery: Int = 100, safeLeft: Int = 0, safeRight: Int = 0) =
        SmallIslandSpectrum.choose(layout, content, 20, clock, battery, safeLeft, safeRight, left, 50)

    @Test fun `current side wins when both sides have sufficient space`() {
        assertEquals(true, choose(true)!!.left)
        assertEquals(false, choose(false)!!.left)
        assertEquals(120, choose(true)!!.frame.x)
        assertEquals(770, choose(false)!!.frame.x)
        assertEquals(content, choose(true)!!.frame.content)
    }

    @Test fun `insufficient current seat keeps the cover instead of moving music to the other side`() {
        assertNull(choose(true, clock = 200))
        assertNull(choose(false, battery = 300))
        assertEquals(false, choose(false, clock = 200)!!.left)
        assertEquals(true, choose(true, battery = 300)!!.left)
        assertNull(choose(false, clock = 200, battery = 300))
    }

    @Test fun `safe insets and the other occupied seat remain protected`() {
        assertNull(choose(true, safeLeft = 200))
        assertNull(choose(false, safeRight = 300))
        assertNull(choose(true, clock = 280)) // No room for the other small island after swapping.
        assertNull(SmallIslandSpectrum.choose(layout.copy(full = true), content, 20, 100, 100, 0, 0, true, 50))
    }

    @Test fun `spectrum allocation does not change main island or leave the safe bounds`() {
        val before = layout.copy()
        for (left in listOf(true, false)) {
            val seat = choose(left)!!
            assertTrue(seat.frame.x >= 100)
            assertTrue(seat.frame.x + seat.frame.width <= 1100)
            if (seat.left) assertEquals(layout.bigX - 20, seat.frame.x + seat.frame.width)
            else assertEquals(layout.bigX + layout.bigWidth + 20, seat.frame.x)
        }
        assertEquals(before, layout)
    }

    @Test fun `spectrum rejection and readmission leave native task seats untouched`() {
        val seats = SmallIslandSeats()
        seats.assign("media", "timer")
        assertNull(choose(seats.left("media")!!, battery = 300))
        seats.assign("timer", "media")
        assertEquals(false, seats.left("media"))
        assertEquals(true, seats.left("timer"))
        assertFalse(choose(seats.left("media")!!)!!.left)
    }

    @Test fun `paired demand keeps the camera pad and compact fallback removes it`() {
        // Verified plugin dimensions at 3.1 px/dp: outer 8dp, icon 20dp, margin 1dp, inner edge 1dp.
        fun px(dp: Int): Int = (dp * 3.1f + 0.5f).toInt()
        val paired = checkNotNull(SmallIslandSpectrum.pairedDemand(px(8), px(20), px(1)))
        assertEquals(IslandContentSize(93, 93), paired)
        assertEquals(IslandContentSize(90, 90),
            checkNotNull(SmallIslandSpectrum.compactDemand(px(8), px(20), px(1), px(1))))
        assertEquals(paired, IslandContentSize(paired.left, paired.right))
        assertNull(SmallIslandSpectrum.pairedDemand(-1, px(20), px(1)))
        assertNull(SmallIslandSpectrum.pairedDemand(px(8), 0, px(1)))
        assertNull(SmallIslandSpectrum.compactDemand(px(8), px(20), px(1), -1))
        assertNull(SmallIslandSpectrum.compactDemand(px(8), px(20), px(1), 200))
        assertNull(SmallIslandSpectrum.pairedDemand(Int.MAX_VALUE, px(20), px(1)))
    }

    @Test fun `side music demand excludes the camera-facing pad its lease removes`() {
        // Verified plugin dimensions at 3.1 px/dp: outer 8dp, icon 20dp, margin 1dp, inner edge 1dp.
        fun px(dp: Int): Int = (dp * 3.1f + 0.5f).toInt()
        val demand = checkNotNull(SmallIslandSpectrum.musicDemand(px(8), px(20), px(1), px(1)))
        assertEquals(IslandContentSize(90, 90), demand)
        val device = ThreeIslandLayout(477, 316, 357, 811, false, 1200)
        // Spare right room is 182 px; reserving the removed 6 px edge would hide the spectrum.
        assertNotNull(SmallIslandSpectrum.choose(device, demand, 18, 239, 207, 0, 0, false, 0))
        assertNotNull(SmallIslandSpectrum.choose(device, demand, 18, 239, 207, 0, 0, false, 102))
    }

    @Test fun `right seat keeps native icons and outside padding after removing camera facing padding`() {
        val device = ThreeIslandLayout(477, 316, 357, 811, false, 1200)
        val before = IslandContentSize(93, 93)
        assertNull(SmallIslandSpectrum.choose(device, before, 18, 239, 207, 0, 0, false, 102))
        val withoutCameraPadding = IslandContentSize(90, 90)
        val seat = SmallIslandSpectrum.choose(device, withoutCameraPadding, 18, 239, 207, 0, 0, false, 102)!!
        assertFalse(seat.left)
        assertEquals(811, seat.frame.x)
        assertEquals(991, seat.frame.x + seat.frame.width)
        assertTrue(seat.frame.x + seat.frame.width <= device.screenWidth - 207)
    }
}
