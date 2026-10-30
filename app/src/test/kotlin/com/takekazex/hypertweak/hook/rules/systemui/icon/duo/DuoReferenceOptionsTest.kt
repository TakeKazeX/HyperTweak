package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.presentation.icon.duo.DuoBattery
import com.takekazex.hypertweak.presentation.icon.duo.DuoContent
import com.takekazex.hypertweak.presentation.icon.duo.DuoDrawable
import com.takekazex.hypertweak.presentation.icon.duo.DuoPolicy
import com.takekazex.hypertweak.presentation.icon.duo.DuoTopGeometry
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import org.junit.Assert.*
import org.junit.Test

class DuoReferenceOptionsTest {
    private val rows = listOf(DuoSignalRow(22, 1, 1), DuoSignalRow(11, 0, -1))
    private val wifi = DuoContent(DuoBattery(45, false, false), 3, null, 1, false, false,
        cellularSignalRows = rows, activeDataSubId = 22)

    @Test fun dualDotsPreserveEachSimsIdentityOrderAndNoService() {
        assertEquals(rows, DuoPolicy.dotRows(wifi, true))
        assertEquals(listOf(1, -1), DuoPolicy.dotRows(wifi, true).map { it.level })
        assertEquals(listOf(22), DuoPolicy.dotRows(wifi, false).map { it.subId })
        assertEquals(listOf(-1), DuoPolicy.dotRows(wifi.copy(noService = true), false).map { it.level })
    }

    @Test fun singleSimAndAirplaneNeverProducePhantomSecondRow() {
        assertEquals(1, DuoPolicy.dotRows(wifi.copy(cellularSignalRows = rows.take(1)), true).size)
        assertTrue(DuoPolicy.dotRows(wifi.copy(airplaneMode = true), true).isEmpty())
        assertTrue(DuoPolicy.dotRows(wifi.copy(wifiLevel = null), true).isEmpty())
        assertTrue(DuoPolicy.dotRows(null, true).isEmpty())
    }

    @Test fun jitterStaysTapWhileDragAndLongPressAreRejected() {
        assertTrue(DuoTapPolicy.isTap(10f, 20f, 14f, 24f, 8f, 100, 500))
        assertFalse(DuoTapPolicy.isTap(10f, 20f, 10f, 30f, 8f, 100, 500))
        assertFalse(DuoTapPolicy.isTap(10f, 20f, 10f, 20f, 8f, 500, 500))
        assertFalse(DuoTapPolicy.isTap(10f, 20f, Float.NaN, 20f, 8f, 100, 500))
    }

    @Test fun newOptionsReachExistingHostsAndSurviveGenerationDisableAndReset() {
        val values = mapOf(
            IconSettingsKeys.KEY_ICON_DUO_TOP_PERCENT to true,
            IconSettingsKeys.KEY_ICON_DUO_DUAL_DOTS to true,
            IconSettingsKeys.KEY_ICON_DUO_CLICK_EXPAND to false
        )
        val target = RuntimeSettingsGroups.target("com.android.systemui")
        assertEquals(values, RuntimeSettingsGroups.subset(target, values))
        values.keys.forEach { assertEquals(setOf(RuntimeSettingsGroups.ICONS), RuntimeSettingsGroups.owners(it)) }
        val old = RuntimeSettingsLedger()
        assertTrue(old.accept(RuntimeSettingsSnapshot.read(1, values, setOf(RuntimeSettingsGroups.ICONS))!!))
        val upgraded = RuntimeSettingsLedger().also { it.restore(old.save()) }
        values.forEach { (key, value) -> assertEquals(value, upgraded.value(key)) }
        assertTrue(upgraded.accept(RuntimeSettingsSnapshot.read(2, values.mapValues { false }, setOf(RuntimeSettingsGroups.ICONS))!!))
        values.keys.forEach { assertEquals(false, upgraded.value(it)) }
        assertTrue(upgraded.accept(RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>(), setOf(RuntimeSettingsGroups.ICONS))!!))
        values.keys.forEach { assertNull(upgraded.value(it)) }
    }

    @Test fun chargingBoltIsIndependentOfNumberAndGapFollowsActualHeaderWidth() {
        assertNull(DuoTopGeometry.header(false, false, 10f, 1f, 1f))
        val bolt = DuoTopGeometry.header(false, true, 10f, 1f, 1f)!!
        assertFalse(bolt.number)
        assertTrue(bolt.bolt)
        val number = DuoTopGeometry.header(true, false, 10f, 1f, 1f)!!
        val both = DuoTopGeometry.header(true, true, 10f, 1f, 1f)!!
        assertTrue(both.number && both.bolt)
        assertTrue(bolt.width < number.width)
        assertTrue(number.width < both.width)
        assertTrue(bolt.halfGapDegrees < number.halfGapDegrees)
        assertTrue(number.halfGapDegrees < both.halfGapDegrees)
        assertTrue("single bolt must not inherit the old 70 degree gap", bolt.halfGapDegrees * 2f < 35f)
        val hundred = DuoTopGeometry.header(true, true, 15f, 1f, 1f)!!
        assertEquals("100 must retain the same readable number height", number.height, hundred.height, .001f)
        assertEquals("charging must not shrink the number", number.scale, both.scale, .001f)
        assertEquals(DuoTopGeometry.NUMBER_HEIGHT, number.height, .001f)
        assertTrue(hundred.width > both.width)
        assertTrue(hundred.halfGapDegrees > both.halfGapDegrees)
        val large = DuoTopGeometry.header(true, true, 15f, 1.5f, 1f)!!
        assertTrue(large.height <= DuoTopGeometry.MAX_HEIGHT)
        assertTrue(large.centerY - large.height / 2f >= DuoTopGeometry.TOP_INSET - .001f)
    }

    @Test fun dualRowsSitInsideTheLowerOpeningAndRemainSeparated() {
        val radius = DuoDrawable.DUAL_DOT_RADIUS * 1.2f
        assertTrue(DuoDrawable.DUAL_DOT_COLUMN_PITCH > 2f * radius)
        assertTrue(DuoDrawable.DUAL_DOT_ROW_PITCH > 2f * radius)
        assertTrue(DuoDrawable.DUAL_DOT_TOP + DuoDrawable.DUAL_DOT_ROW_PITCH + radius <
            DuoDrawable.COMPACT_RING_CENTER_Y + DuoDrawable.TRACK_RADIUS * DuoDrawable.COMPACT_RING_SCALE)
        assertTrue("top row meets the ring ends instead of hanging below them",
            DuoDrawable.DUAL_DOT_TOP - radius < 22f)
    }
}
