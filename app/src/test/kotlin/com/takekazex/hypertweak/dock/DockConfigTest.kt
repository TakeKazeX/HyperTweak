package com.takekazex.hypertweak.dock

import org.junit.Assert.*
import org.junit.Test

class DockConfigTest {
    @Test fun incompleteOrFutureSnapshotCannotPartiallyApplyGeometry() {
        listOf("", "1|1|0|88|16|12", "4|1|0|88|16|12|28", "1|1|0|88|16|12|999", "1|1|9|88|16|12|28",
            "1|true|0|88|16|12|28", "1|1|0|88|-1|12|28", "1|1|0|0|16|12|28").forEach { assertNull(it, DockConfig.decode(it)) }
    }
    @Test fun discardedV4TuningRestoresMaterialDefaultsWithoutMovingTheDock() {
        assertEquals(DockConfig(enabled = true), DockConfig.decode("3|1|0|94|10|36|28"))
        val old = "4|1|1|130|24|12|44|200|0|180|25"
        assertEquals(DockConfig(true, DockConfig.FROSTED, 130, 24, 12, 44), DockConfig.decode(old))
        val tuned = DockConfig(enabled = true, blur = 0, tint = 35, opacity = 25)
        assertEquals(tuned, DockConfig.decode(tuned.encode()))
    }
    @Test fun malformedOrOutOfRangeMaterialSnapshotsAreRejectedAsOneTransaction() {
        listOf("4|1|0|94|10|36|28|100|100|100", "4|1|0|94|10|36|28|201|100|100|100",
            "4|1|0|94|10|36|28|100|101|100|100", "4|1|0|94|10|36|28|100|100|-1|100",
            "4|1|0|94|10|36|28|100|100|100|201", "3|1|0|94|10|36|28|100|100|100|100",
            "5|1|0|94|10|36|28|100|100", "5|1|0|94|10|36|28|161|100|100",
            "5|1|0|94|10|36|28|100|101|100", "5|1|0|94|10|36|28|100|100|-1",
            "5|1|0|94|10|36|28|100|100|101", "6|1|0|94|10|36|28|100|100|100")
            .forEach { assertNull(it, DockConfig.decode(it)) }
    }
    @Test fun opacityChangesOnlyTheFinalNativeAlphaAndNeverMutatesItsToken() {
        val native = FloatArray(42) { it / 10f }.also { it[18] = .43f }
        assertArrayEquals(native, DockConfig().glassShader(native), 0f)
        val adjusted = DockConfig(opacity = 50).glassShader(native)
        assertEquals(.215f, adjusted[18], 0.000001f)
        native.indices.filter { it != 18 }.forEach { assertEquals(native[it], adjusted[it], 0f) }
        assertEquals(.43f, native[18], 0f)
        assertEquals(0f, DockConfig(opacity = 0).glassShader(native)[18], 0f)
    }
    @Test fun previewProjectsBlurAndPhysicalGlassShapeWithTheSameGeometryScale() {
        val native = FloatArray(42) { it / 10f }
        val config = DockConfig(blur = 120)
        val preview = config.glassShader(native, .5f)
        assertEquals(60, config.blurPixels(.5f))
        listOf(19, 21, 22).forEach { assertEquals(native[it] / 2, preview[it], 0f) }
        native.indices.filter { it !in listOf(19, 21, 22) }.forEach { assertEquals(native[it], preview[it], 0f) }
        assertEquals(0, DockConfig(blur = 0).blurPixels(.001f))
        assertEquals(1, DockConfig(blur = 1).blurPixels(.001f))
    }
    @Test fun materialInvalidationIncludesActiveParametersWithoutRecreatingGeometry() {
        val glass = DockConfig()
        assertNotEquals(glass.materialKey(false), glass.copy(blur = 10).materialKey(false))
        assertNotEquals(glass.materialKey(false), glass.copy(opacity = 45).materialKey(false))
        assertNotEquals(glass.materialKey(false), glass.materialKey(true))
        assertEquals(glass.materialKey(false), glass.copy(tint = 45).materialKey(false))
        val frosted = glass.copy(style = DockConfig.FROSTED)
        assertNotEquals(frosted.materialKey(false), frosted.copy(tint = 45).materialKey(false))
        assertEquals(frosted.materialKey(false), frosted.copy(opacity = 45).materialKey(false))
        assertEquals(glass.materialKey(false), glass.copy(height = 130).materialKey(false))
        assertEquals(DockGeometry.resolve(1200, 2608, 3f, glass),
            DockGeometry.resolve(1200, 2608, 3f, glass.copy(blur = 10, opacity = 45)))
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
