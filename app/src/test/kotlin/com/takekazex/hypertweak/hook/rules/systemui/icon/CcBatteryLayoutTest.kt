package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the control-center battery pair placement.
 *
 * `MiuiBatteryMeterView.onLayout` positions the icon container at the leading edge and appends the
 * percentage container after it, so the outside percentage is always on the trailing side. The
 * "left" switch re-lays the pair on the leading side; the arithmetic is device-independent, and a
 * regression here would move the percentage under the icon or off the view.
 */
class CcBatteryLayoutTest {

    @Test
    fun ltrPutsThePercentFirstAndKeepsThePairWidth() {
        val (percent, icon) = CcBatteryLayout.percentLeading(
            leading = CcBatteryLayout.leadingEdge(viewWidth = 200, paddingStart = 6, rtl = false),
            rtl = false,
            iconWidth = 30,
            percentWidth = 24
        )

        assertEquals(CcBatterySpan(6, 30), percent)
        assertEquals(CcBatterySpan(30, 60), icon)
    }

    @Test
    fun rtlMirrorsTheSamePairInsideTheTrailingPadding() {
        val (percent, icon) = CcBatteryLayout.percentLeading(
            leading = CcBatteryLayout.leadingEdge(viewWidth = 200, paddingStart = 6, rtl = true),
            rtl = true,
            iconWidth = 30,
            percentWidth = 24
        )

        // The leading edge is the right side in RTL: the icon keeps the outer edge and the
        // percentage sits inboard of it.
        assertEquals(CcBatterySpan(170, 194), percent)
        assertEquals(CcBatterySpan(140, 170), icon)
    }

    @Test
    fun thePairSpansTheSameTotalWidthInBothDirections() {
        val ltr = CcBatteryLayout.percentLeading(0, false, iconWidth = 30, percentWidth = 24)
        val rtl = CcBatteryLayout.percentLeading(0, true, iconWidth = 30, percentWidth = 24)

        assertEquals(54, ltr.second.right - ltr.first.left)
        assertEquals(54, rtl.first.right - rtl.second.left)
    }

    @Test
    fun zeroPaddingStartUsesTheViewEdge() {
        assertEquals(0, CcBatteryLayout.leadingEdge(viewWidth = 120, paddingStart = 0, rtl = false))
        assertEquals(120, CcBatteryLayout.leadingEdge(viewWidth = 120, paddingStart = 0, rtl = true))
    }
}
