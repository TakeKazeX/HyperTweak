package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.*
import org.junit.Test

class TwoRowIconLayoutTest {
    @Test fun rotationUsesCurrentBatteryBoundsAndAlwaysKeepsNfcOnTheFirstRow() {
        val nfc = TwoRowIconLayout.Item(0, 20f, true)
        val bluetooth = TwoRowIconLayout.Item(1, 18f, false)
        fun layout(batteryLeft: Int, batteryTop: Int, containerLeft: Int, containerTop: Int): Pair<Map<Int, Float>, TwoRowIconLayout.Anchors> {
            val anchors = TwoRowIconLayout.anchors(batteryLeft, batteryTop, 40, 24,
                containerLeft, containerTop, 27f)
            return TwoRowIconLayout.positions(listOf(nfc, bluetooth), anchors.upperRight,
                anchors.lowerRight, 4f, false) to anchors
        }
        val landscape = layout(700, 8, 400, 8)
        val portrait = layout(300, 12, 100, 12)
        assertEquals(280f, landscape.first.getValue(0), 0f)
        assertEquals(180f, portrait.first.getValue(0), 0f)
        assertEquals(12f, landscape.second.firstCenter, 0f)
        assertEquals(39f, landscape.second.secondCenter, 0f)
        // Moving the ancestor/header or changing both siblings' origin cannot affect packing.
        assertEquals(landscape, layout(1700, 108, 1400, 108))
        repeat(3) {
            layout(300, 12, 100, 12)
            assertEquals(landscape, layout(700, 8, 400, 8))
        }
    }

    @Test fun differentSiblingTopAndLargeRowsStillUseTheCurrentFirstRow() {
        val anchors = TwoRowIconLayout.anchors(410, 4, 50, 40, 10, 8, 43f)
        assertEquals(16f, anchors.firstCenter, 0f)
        assertEquals(59f, anchors.secondCenter, 0f)
        assertEquals(400f, anchors.upperRight, 0f)
        assertEquals(450f, anchors.lowerRight, 0f)
    }

    @Test fun phoneHeaderKeepsTwoRowsAcrossRotationWithoutEnablingTabletOrTinyWindows() {
        for ((width, height) in listOf(300 to 700, 393 to 873, 599 to 960)) {
            assertTrue(TwoRowIconLayout.supportsPhoneHeader(width, height))
            assertTrue(TwoRowIconLayout.supportsPhoneHeader(height, width))
        }
        for ((width, height) in listOf(600 to 960, 800 to 1280, 299 to 700, 393 to 280, 0 to 873)) {
            assertFalse(TwoRowIconLayout.supportsPhoneHeader(width, height))
            assertFalse(TwoRowIconLayout.supportsPhoneHeader(height, width))
        }
    }

    private val icons = listOf(
        TwoRowIconLayout.Item(0, 12f, false), // Bluetooth
        TwoRowIconLayout.Item(1, 16f, true),  // Mobile
        TwoRowIconLayout.Item(2, 10f, false), // Alarm
        TwoRowIconLayout.Item(3, 20f, true)   // Wi-Fi
    )
    private fun positions(items: List<TwoRowIconLayout.Item>, rtl: Boolean = false) =
        TwoRowIconLayout.positions(items, upperRight = 100f, lowerRight = 124f, spacing = 4f, rtl = rtl)

    @Test fun oneSimCollapsesHeightButKeepsTheBatteryAnchorAcrossSimChanges() {
        val dual = TwoRowIconLayout.geometry(listOf(24, 24), 24, 3, paddingTop = 2, paddingBottom = 2)
        val single = TwoRowIconLayout.geometry(listOf(24), 24, 3, paddingTop = 2, paddingBottom = 2)
        assertEquals(2, dual.rowCount)
        assertEquals(55, dual.minimumHeight)
        assertEquals(1, single.rowCount)
        assertEquals(28, single.minimumHeight)
        assertEquals(dual.firstCenter, single.firstCenter, 0f)
        assertEquals(single.firstCenter, single.secondCenter, 0f)
        repeat(3) {
            assertEquals(single, TwoRowIconLayout.geometry(listOf(24), 24, 3, 2, 2))
            assertEquals(dual, TwoRowIconLayout.geometry(listOf(24, 24), 24, 3, 2, 2))
        }
        val largeFont = TwoRowIconLayout.geometry(listOf(40), 24, 3, 2, 2)
        assertEquals(44, largeFont.minimumHeight)
        assertTrue(largeFont.firstCenter + 20 <= largeFont.minimumHeight)
    }

    @Test fun simCountOwnsRowsEvenBeforeNativeVisibilityAndMeasurementCatchUp() {
        val oneSim = TwoRowIconLayout.geometry(listOf(24, 24), 24, 3, rowCount = 1)
        assertEquals(1, oneSim.rowCount)
        assertEquals(24, oneSim.minimumHeight)
        val twoSims = TwoRowIconLayout.geometry(listOf(24), 24, 3, rowCount = 2)
        assertEquals(2, twoSims.rowCount)
        assertEquals(51, twoSims.minimumHeight)
        val empty = TwoRowIconLayout.geometry(emptyList(), 24, 3)
        assertEquals(1, empty.rowCount)
        assertEquals(24, empty.minimumHeight)
        assertEquals(empty.firstCenter, empty.secondCenter, 0f)
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
            assertEquals(24, geometry.minimumHeight)
        }
        val dual = TwoRowIconLayout.carrierRowTops(listOf(0 to 24, 1 to 24),
            TwoRowIconLayout.geometry(listOf(24, 24), 24, 3))
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
