package com.takekazex.hypertweak.dock

import org.junit.Assert.*
import org.junit.Test

class DockConfigTest {
    @Test fun incompleteOrFutureSnapshotCannotPartiallyApplyGeometry() {
        listOf("", "1|1|0|88|16|12", "4|1|0|88|16|12|28", "1|1|0|88|16|12|999", "1|1|9|88|16|12|28",
            "1|true|0|88|16|12|28", "1|1|0|88|-1|12|28", "1|1|0|0|16|12|28").forEach { assertNull(it, DockConfig.decode(it)) }
    }
    @Test fun disableAndIndependentGeometrySurvivePortableRoundTrip() {
        val value = DockConfig(false, DockConfig.FROSTED, 112, 24, 31, 44)
        assertEquals(value, DockConfig.decode(value.encode()))
    }
    @Test fun measuredDefaultsCenterBackgroundOnLauncherIconRow() {
        val config = DockConfig()
        val panel = requireNotNull(DockGeometry.resolve(1200, 2608, 3f, config))
        val icons = DockPreviewGeometry.icons(1200, 2608, 3f)
        assertEquals(DockBounds(30, 2218, 1140, 282, 84f), panel)
        assertEquals(listOf(75, 357, 639, 921), icons.map { it.x })
        icons.forEach {
            assertEquals(2257, it.y)
            assertEquals(204, it.height)
            assertTrue(it.y >= panel.y && it.y + it.height <= panel.y + panel.height)
            assertTrue(kotlin.math.abs((it.y + it.height / 2) - (panel.y + panel.height / 2)) <= 3)
        }
    }
    @Test fun legacyDefaultMigratesButCustomizedGeometryAndNewExplicitTwelveRemainExact() {
        assertEquals(DockConfig(enabled = true, style = DockConfig.FROSTED), DockConfig.decode("1|1|1|88|16|12|28"))
        assertEquals(DockConfig(enabled = true), DockConfig.decode("2|1|0|88|16|40|28"))
        assertEquals(40, DockConfig.decode("2|1|0|88|16|40|30")!!.bottomMargin)
        assertEquals(12, DockConfig.decode("1|1|0|90|16|12|28")!!.bottomMargin)
        val explicit = DockConfig(bottomMargin = 12)
        assertEquals(explicit, DockConfig.decode(explicit.encode()))
    }
    @Test fun bottomMarginIsMeasuredOnceFromScreenEdgeAcrossDensities() {
        val config = DockConfig(height = 80, horizontalMargin = 20, bottomMargin = 12, radius = 60)
        val bounds = requireNotNull(DockGeometry.resolve(1200, 2608, 3f, config))
        assertEquals(60, bounds.x)
        assertEquals(1080, bounds.width)
        assertEquals(240, bounds.height)
        assertEquals(2608 - 36, bounds.y + bounds.height)
        assertEquals(120f, bounds.radius)
    }
    @Test fun previewProjectionAndRealBackgroundUseIdenticalGeometry() {
        val config = DockConfig()
        val real = requireNotNull(DockGeometry.resolve(1200, 600, 3f, config))
        val preview = requireNotNull(DockGeometry.resolve(600, 300, 1.5f, config))
        assertEquals(real.x / 2, preview.x)
        assertEquals(real.y / 2, preview.y)
        assertEquals(real.width / 2, preview.width)
        assertEquals(real.height / 2, preview.height)
        assertEquals(real.radius / 2, preview.radius)
    }
    @Test fun tinyOrUnmeasuredWindowDoesNotCreateANegativeSurface() {
        assertNull(DockGeometry.resolve(0, 100, 3f, DockConfig()))
        assertNull(DockGeometry.resolve(400, 0, 3f, DockConfig()))
        assertNull(DockGeometry.resolve(40, 100, 3f, DockConfig()))
        assertNull(DockGeometry.resolve(400, 100, Float.NaN, DockConfig()))
        assertNull(DockGeometry.resolve(400, 100, 3f, DockConfig(bottomMargin = 100)))
    }
    @Test fun startingRecentsAndForeignWindowsAreNeverDecorated() {
        assertTrue(DockGeometry.isHomeWindow("com.miui.home", "com.miui.home/com.miui.home.launcher.Launcher", 1, 0))
        assertFalse(DockGeometry.isHomeWindow("com.other", "com.miui.home/com.miui.home.launcher.Launcher", 1, 0))
        assertFalse(DockGeometry.isHomeWindow("com.miui.home", "Recents", 1, 0))
        assertFalse(DockGeometry.isHomeWindow("com.miui.home", "com.miui.home/com.miui.home.launcher.Launcher", 3, 0))
        assertFalse(DockGeometry.isHomeWindow("com.miui.home", "com.miui.home/com.miui.home.launcher.Launcher", 1, 1))
    }
    @Test fun minusOneAndSearchOverlaysHaveADistinctVisibilityBoundary() {
        assertTrue(DockGeometry.isHomeOverlay("com.miui.home", "LauncherOverlayWindow:com.miui.personalassistant", 4, 0))
        assertTrue(DockGeometry.isHomeOverlay("com.miui.home", "LauncherOverlayWindow:com.android.quicksearchbox", 4, 0))
        assertFalse(DockGeometry.isHomeOverlay("com.miui.home", "GestureStubLeft", 2027, 0))
        assertFalse(DockGeometry.isHomeOverlay("com.other", "LauncherOverlayWindow:com.miui.personalassistant", 4, 0))
        assertFalse(DockGeometry.isHomeOverlay("com.miui.home", "LauncherOverlayWindow:com.miui.personalassistant", 4, 1))
    }
}
