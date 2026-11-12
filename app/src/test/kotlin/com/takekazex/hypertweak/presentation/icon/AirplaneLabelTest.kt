package com.takekazex.hypertweak.presentation.icon

import com.takekazex.hypertweak.presentation.icon.AirplaneLabelGeometry.Order
import com.takekazex.hypertweak.settings.contract.AirplaneLabelMode
import com.takekazex.hypertweak.settings.contract.AirplaneLabelModes
import com.takekazex.hypertweak.settings.contract.AirplaneLabelPolicy
import com.takekazex.hypertweak.settings.contract.AirplaneLabelSurface
import com.takekazex.hypertweak.settings.contract.IconConfigDecoder
import com.takekazex.hypertweak.settings.contract.IconConfigReload
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconTunerContract
import com.takekazex.hypertweak.settings.contract.IndependentIconDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AirplaneLabelTest {
    private fun config(values: Map<String, Any>) = IconConfigDecoder.decode { values[it] }

    @Test fun storedValuesDecodePerSurfaceAndInvalidValuesStayNative() {
        val modes = config(mapOf(
            IconSettingsKeys.KEY_ICON_AIRPLANE_LABEL_CONTROL_CENTER to 2,
            IconSettingsKeys.KEY_ICON_AIRPLANE_LABEL_NOTIFICATION to 9,
            IconSettingsKeys.KEY_ICON_AIRPLANE_LABEL_KEYGUARD to 1
        )).misc.airplaneLabelModes
        assertEquals(AirplaneLabelModes(AirplaneLabelMode.ICON, AirplaneLabelMode.SYSTEM, AirplaneLabelMode.HIDE), modes)
        assertFalse(config(emptyMap()).misc.airplaneLabelModes.customized)
        assertEquals(AirplaneLabelMode.entries.map { it.storedValue }, AirplaneLabelMode.RANGE.toList())
    }

    @Test fun labelChangesReachTheLabelAndCompactRowOwnersOnly() {
        val base = config(emptyMap())
        assertEquals(
            setOf(IndependentIconDomain.AIRPLANE, IndependentIconDomain.CONTROL_CENTER_HEADER),
            IconConfigReload.domains(base, config(mapOf(IconSettingsKeys.KEY_ICON_AIRPLANE_LABEL_KEYGUARD to 3)))
        )
        assertEquals(
            setOf(IndependentIconDomain.AIRPLANE, IndependentIconDomain.DUO, IndependentIconDomain.CONTROL_CENTER_HEADER),
            IconConfigReload.domains(base, config(mapOf(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE to true)))
        )
    }

    @Test fun leftPlacementNeverReloadsTheLabel() {
        val glyph = mapOf<String, Any>(
            IconTunerContract.KEY_LEFT_MODE to IconTunerContract.LEFT_MODE_HOME_AND_LOCKSCREEN,
            IconSettingsKeys.KEY_ICON_AIRPLANE_LABEL_KEYGUARD to 2
        )
        val moved = glyph + (IconSettingsKeys.KEY_ICON_LEFT_AIRPLANE to true)
        // Every surface keeps its own mode; the lockscreen glyph travels into a left clone instead.
        assertFalse(IndependentIconDomain.AIRPLANE in IconConfigReload.domains(config(glyph), config(moved)).orEmpty())
        assertEquals(AirplaneLabelMode.ICON, config(moved).misc.airplaneLabelModes[AirplaneLabelSurface.KEYGUARD])
    }

    @Test fun globalLeftModeChangesDoNotRetireLabelOrCompactEndpoints() {
        val glyphs = mapOf<String, Any>(
            IconSettingsKeys.KEY_ICON_AIRPLANE_LABEL_KEYGUARD to 2,
            IconSettingsKeys.KEY_ICON_AIRPLANE_LABEL_CONTROL_CENTER to 2
        )
        val off = config(glyphs)
        val on = config(glyphs + (IconTunerContract.KEY_LEFT_MODE to IconTunerContract.LEFT_MODE_HOME_AND_LOCKSCREEN))
        assertEquals(setOf(IndependentIconDomain.POLICY), IconConfigReload.domains(off, on))
        assertEquals(setOf(IndependentIconDomain.POLICY), IconConfigReload.domains(on, off))
    }

    @Test fun surfacesNeedTheHostIdAndAMatchingKeyguardFlag() {
        assertEquals(AirplaneLabelSurface.CONTROL_CENTER,
            AirplaneLabelPolicy.surfaceFor("normal_control_center_carrier_layout", false))
        assertEquals(AirplaneLabelSurface.NOTIFICATION,
            AirplaneLabelPolicy.surfaceFor("normal_notification_center_carrier_view", false))
        assertEquals(AirplaneLabelSurface.NOTIFICATION,
            AirplaneLabelPolicy.surfaceFor("notification_shade_carrier_layout", false))
        assertEquals(AirplaneLabelSurface.KEYGUARD, AirplaneLabelPolicy.surfaceFor("keyguard_carrier_layout", true))
        assertNull(AirplaneLabelPolicy.surfaceFor("keyguard_carrier_layout", false))
        assertNull(AirplaneLabelPolicy.surfaceFor("normal_control_center_carrier_layout", true))
        assertNull(AirplaneLabelPolicy.surfaceFor(null, false))
    }

    @Test fun glyphSpansTheLineBoxAndKeepsTheVectorRatio() {
        val box = AirplaneLabelGeometry.box(-30f, 10f, 58, 56, 3f, Order.GLYPH_ONLY, rtl = false)
        assertEquals(40f, box.glyphHeight, 0.001f)
        assertEquals(40f * 58 / 56, box.glyphWidth, 0.001f)
        assertEquals(-30f, box.glyphTop, 0.001f)
        // The replaced label carries no gap.
        assertEquals(42, box.advance)
        assertEquals(0f, box.glyphX, 0.001f)
    }

    @Test fun gapFacesTheLabelInBothDirections() {
        fun x(order: Order, rtl: Boolean) = AirplaneLabelGeometry.box(-10f, 10f, 20, 20, 4f, order, rtl).glyphX
        assertEquals(0f, x(Order.GLYPH_FIRST, rtl = false), 0.001f)
        assertEquals(4f, x(Order.TEXT_FIRST, rtl = false), 0.001f)
        // In RTL the logically first glyph is drawn right of the label.
        assertEquals(4f, x(Order.GLYPH_FIRST, rtl = true), 0.001f)
        assertEquals(0f, x(Order.TEXT_FIRST, rtl = true), 0.001f)
        assertEquals(24, AirplaneLabelGeometry.box(-10f, 10f, 20, 20, 4f, Order.TEXT_FIRST, false).advance)
    }

    @Test fun modesMapToGlyphOrder() {
        assertNull(AirplaneLabelGeometry.order(AirplaneLabelMode.SYSTEM))
        assertNull(AirplaneLabelGeometry.order(AirplaneLabelMode.HIDE))
        assertEquals(Order.GLYPH_ONLY, AirplaneLabelGeometry.order(AirplaneLabelMode.ICON))
        assertEquals(Order.GLYPH_FIRST, AirplaneLabelGeometry.order(AirplaneLabelMode.ICON_START))
        assertEquals(Order.TEXT_FIRST, AirplaneLabelGeometry.order(AirplaneLabelMode.ICON_END))
    }
}
