package com.takekazex.hypertweak.presentation.launcher

import com.takekazex.hypertweak.settings.contract.NativeDockGlassGeometry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class NativeDockGlassPreviewGeometryTest {
    @Test fun adaptiveGlassKeepsTheDefaultFullRowPaddingAroundFewerCenteredIcons() {
        for (width in listOf(360, 720, 1080)) for (margin in listOf(0, 5, 24)) {
            val density = width / 400f
            val height = width * 2
            // Adaptive width locks the side margin to the default full-row margin.
            val full = NativeDockGlassGeometry.DEFAULT
            val adaptive = full.copy(margin = margin, adaptive = true)
            assertEquals(full.margin, adaptive.effectiveMargin)
            val fullGlass = NativeDockGlassPreviewGeometry.resolve(width, height, density, full)!!
            val glass = NativeDockGlassPreviewGeometry.resolve(width, height, density, adaptive)!!
            val fullIcons = NativeDockGlassPreviewGeometry.icons(width, height, density)
            val icons = NativeDockGlassPreviewGeometry.icons(width, height, density,
                NativeDockGlassPreviewGeometry.iconCount(adaptive))
            assertEquals(NativeDockGlassPreviewGeometry.ADAPTIVE_SAMPLE_ICONS, icons.size)
            // Same cell pitch, centered row, and the same glass-to-icon padding as a full row.
            assertNear(fullIcons[1].x - fullIcons[0].x, icons[1].x - icons[0].x)
            assertNear(width - (icons.last().x + icons.last().width), icons.first().x)
            assertNear(fullIcons.first().x - fullGlass.x, icons.first().x - glass.x)
            assertNear(width, glass.x * 2 + glass.width)
            assertEquals(fullGlass.height, glass.height)
        }
    }

    private fun assertNear(expected: Int, actual: Int) = assertTrue("$expected vs $actual", abs(expected - actual) <= 1)
}
