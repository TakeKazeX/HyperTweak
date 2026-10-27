package com.takekazex.hypertweak.hook.rules.ime

import org.junit.Assert.*
import org.junit.Test

class ImeNativeBottomPolicyTest {
    @Test fun keysAndToolbarsCannotSupplyTheBottomSurfaceColor() {
        assertFalse(ImeNativeBottomPolicy.isSurface(100, 120, 1080, 700))
        assertFalse(ImeNativeBottomPolicy.isSurface(1080, 100, 1080, 700))
        assertFalse(ImeNativeBottomPolicy.isSurface(700, 700, 1080, 700))
        assertTrue(ImeNativeBottomPolicy.isSurface(1080, 600, 1080, 700))
        assertTrue(ImeNativeBottomPolicy.isSurface(810, 175, 1080, 700))
    }

    @Test fun unmeasuredViewsAreNotSurfacesAndLargeDimensionsDoNotOverflow() {
        assertFalse(ImeNativeBottomPolicy.isSurface(0, 0, 0, 0))
        assertFalse(ImeNativeBottomPolicy.isSurface(0, 0, 1080, 700))
        assertTrue(ImeNativeBottomPolicy.isSurface(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun nativeIconsFollowDarkAndLightThemesInsteadOfTheSystemDefault() {
        for (color in intArrayOf(0xFF1B1A23.toInt(), 0xFF202020.toInt(), 0xFF000000.toInt(), 0xFF0000FF.toInt())) {
            assertEquals(0xFFFFFFFF.toInt(), ImeNativeBottomPolicy.iconColor(color))
        }
        for (color in intArrayOf(0xFFFFFFFF.toInt(), 0xFFCCCCCC.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt())) {
            assertEquals(0xFF000000.toInt(), ImeNativeBottomPolicy.iconColor(color))
        }
    }

    @Test fun midGrayContrastCrossingUsesLinearLightRatherThanRgbAverage() {
        assertEquals(0xFFFFFFFF.toInt(), ImeNativeBottomPolicy.iconColor(0xFF757575.toInt()))
        assertEquals(0xFF000000.toInt(), ImeNativeBottomPolicy.iconColor(0xFF767676.toInt()))
    }
}
