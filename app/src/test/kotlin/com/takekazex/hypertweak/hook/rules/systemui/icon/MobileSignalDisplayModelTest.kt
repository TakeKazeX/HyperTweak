package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.presentation.icon.MobileSignalModel
import com.takekazex.hypertweak.presentation.icon.MobileSignalKind
import org.junit.Assert.*
import org.junit.Test

class MobileSignalDisplayModelTest {
    private val icons = MobileSignalDisplayModel.levels(listOf(intArrayOf(10, 11, 12, 13, 14), intArrayOf(20, 21, 22, 23, 24)))

    @Test fun finalHostStrengthWinsAndGenericUpdatesCannotOverwriteIt() {
        val source = MobileSignalDisplayModel(icons)
        source.model(MobileSignalModel.cellular(3, 5, showExclamationMark = true))
        assertEquals(4, source.icon(14).level)
        val update = source.model(MobileSignalModel.cellular(2, 5, carrierNetworkChange = true))
        assertEquals(4, update.level)
        assertTrue(update.carrierNetworkChange)
        assertEquals(1, source.icon(21).level)
    }

    @Test fun lateModelsAndDifferentSubscriptionsDoNotMixStrength() {
        val first = MobileSignalDisplayModel(icons)
        val second = MobileSignalDisplayModel(icons)
        assertEquals(MobileSignalKind.UNKNOWN, first.icon(14).kind)
        assertEquals(4, first.model(MobileSignalModel.cellular(3, 5)).level)
        second.model(MobileSignalModel.cellular(2, 5))
        assertEquals(0, second.icon(10).level)
        assertEquals(4, first.model(MobileSignalModel.cellular(1, 5)).level)
    }

    @Test fun unknownArtworkFallsBackWithoutInventingServiceOrStrength() {
        val source = MobileSignalDisplayModel(icons)
        val generic = MobileSignalModel.cellular(3, 6, showExclamationMark = true)
        source.model(generic); source.icon(14)
        assertEquals(generic, source.icon(999))
        source.icon(14)
        val satellite = MobileSignalModel.satellite(2, 5)
        assertEquals(satellite, source.model(satellite))
        assertEquals(MobileSignalModel.unknown(), source.model(MobileSignalModel.unknown()))
    }

    @Test fun runtimeTablesIncludeAllLevelsAndRejectAmbiguousIds() {
        assertEquals((0..4).toList(), (10..14).map { icons[it] })
        assertEquals((0..4).toList(), (20..24).map { icons[it] })
        assertTrue(MobileSignalDisplayModel.levels(listOf(intArrayOf(0, 1))).isEmpty())
        val ambiguous = MobileSignalDisplayModel.levels(listOf(intArrayOf(10, 11), intArrayOf(11, 12)))
        assertFalse(ambiguous.containsKey(11))
        assertEquals(0, ambiguous[10]); assertEquals(4, ambiguous[12])
    }
}
