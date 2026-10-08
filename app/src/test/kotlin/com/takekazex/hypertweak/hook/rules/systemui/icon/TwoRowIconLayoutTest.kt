package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.*
import org.junit.Test

class TwoRowIconLayoutTest {
    private val icons = listOf(
        TwoRowIconLayout.Item(0, 12f, false), // Bluetooth
        TwoRowIconLayout.Item(1, 16f, true),  // Mobile
        TwoRowIconLayout.Item(2, 10f, false), // Alarm
        TwoRowIconLayout.Item(3, 20f, true)   // Wi-Fi
    )
    private fun positions(items: List<TwoRowIconLayout.Item>, rtl: Boolean = false) =
        TwoRowIconLayout.positions(items, upperRight = 100f, lowerRight = 124f, spacing = 4f, rtl = rtl)

    @Test fun oneRemainingSimStillReservesBothRowsAndBatteryAnchor() {
        val dual = TwoRowIconLayout.geometry(listOf(24, 24), 24, 3, paddingTop = 2, paddingBottom = 2)
        val single = TwoRowIconLayout.geometry(listOf(24), 24, 3, paddingTop = 2, paddingBottom = 2)
        assertEquals(dual, single)
        assertEquals(14f, single.firstCenter, 0f)
        assertEquals(41f, single.secondCenter, 0f)
        assertEquals(55, single.minimumHeight)
        assertTrue(single.secondCenter + 12 <= single.minimumHeight)
        val largeFont = TwoRowIconLayout.geometry(listOf(24, 40), 24, 3)
        assertTrue(largeFont.secondCenter + 20 <= largeFont.minimumHeight)
    }

    @Test fun disablingEitherSimKeepsTheRemainingCarrierAtTheFirstAnchor() {
        assertEquals(mapOf(0 to 0, 1 to 3), TwoRowIconLayout.visibleRowMargins(listOf(0, 1), 3))
        assertEquals(mapOf(0 to 0), TwoRowIconLayout.visibleRowMargins(listOf(0), 3))
        assertEquals(mapOf(1 to 0), TwoRowIconLayout.visibleRowMargins(listOf(1), 3))
        assertTrue(TwoRowIconLayout.visibleRowMargins(emptyList(), 3).isEmpty())
        val restored = TwoRowIconLayout.visibleRowMargins(listOf(0, 1), 3)
        assertEquals(3, restored.getValue(1))
    }

    @Test fun eitherSingleCarrierIsPinnedToTheFirstRowInsteadOfTheCanvasCenter() {
        val geometry = TwoRowIconLayout.geometry(listOf(24), 24, 3)
        for (slot in listOf(0, 1)) {
            val tops = TwoRowIconLayout.carrierRowTops(listOf(slot to 24), geometry)
            assertEquals(0, tops.getValue(slot))
            assertEquals(geometry.firstCenter, tops.getValue(slot) + 12f, 0f)
            assertTrue(tops.getValue(slot) + 12f < geometry.minimumHeight / 2f)
        }
        val dual = TwoRowIconLayout.carrierRowTops(listOf(0 to 24, 1 to 24), geometry)
        assertEquals(mapOf(0 to 0, 1 to 27), dual)
    }

    @Test fun shorterCarrierAlignsWithTheSharedFirstAnchorAtLargeRowHeight() {
        val geometry = TwoRowIconLayout.geometry(listOf(20, 40), 24, 3, paddingTop = 4)
        val tops = TwoRowIconLayout.carrierRowTops(listOf(1 to 20), geometry)
        assertEquals(geometry.firstCenter, tops.getValue(1) + 10f, 0f)
        assertTrue(tops.getValue(1) + 20 <= geometry.minimumHeight)
    }

    @Test fun networkAndStatusRowsPackIndependentlyAroundTheBattery() {
        val result = positions(icons)
        assertEquals(80f, result.getValue(3), 0f)
        assertEquals(60f, result.getValue(1), 0f)
        assertEquals(114f, result.getValue(2), 0f)
        assertEquals(98f, result.getValue(0), 0f)
        assertTrue(result.getValue(3) + 20f <= 100f)
        assertEquals(124f, result.getValue(2) + 10f, 0f)
    }

    @Test fun hidingNetworkDoesNotRepackTheLowerRow() {
        val before = positions(icons)
        val after = positions(icons.filterNot { it.index == 3 })
        assertFalse(after.containsKey(3))
        assertEquals(before[0], after[0])
        assertEquals(before[2], after[2])
        assertEquals(84f, after.getValue(1), 0f)
    }

    @Test fun hidingLowerIndicatorDoesNotReserveAnEmptySlotOrMoveNetworks() {
        val before = positions(icons)
        val after = positions(icons.filterNot { it.index == 2 })
        assertEquals(before[1], after[1])
        assertEquals(before[3], after[3])
        assertEquals(112f, after.getValue(0), 0f)
    }

    @Test fun rtlUsesNativeChildOrderWithinEachRow() {
        val result = positions(icons, rtl = true)
        assertEquals(84f, result.getValue(1), 0f)
        assertEquals(60f, result.getValue(3), 0f)
        assertEquals(112f, result.getValue(0), 0f)
        assertEquals(98f, result.getValue(2), 0f)
    }

    @Test fun mixedInputOrderDoesNotChangePlacementAndEmptyRowReservesNothing() {
        assertEquals(positions(icons), positions(icons.reversed()))
        val lowerOnly = icons.filterNot { it.upper }
        assertEquals(positions(icons).filterKeys { it == 0 || it == 2 }, positions(lowerOnly))
        assertTrue(positions(emptyList()).isEmpty())
    }
}
