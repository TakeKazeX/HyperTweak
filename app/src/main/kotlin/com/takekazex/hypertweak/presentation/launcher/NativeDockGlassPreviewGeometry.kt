package com.takekazex.hypertweak.presentation.launcher

import com.takekazex.hypertweak.settings.contract.NativeDockGlassGeometry
import kotlin.math.min
import kotlin.math.roundToInt

internal data class DockPreviewBounds(val x: Int, val y: Int, val width: Int, val height: Int, val radius: Float)

/**
 * Local illustration of the native Dock glass. The launcher insets the glass inside its phone
 * Hotseat area; the area dimensions are a device measurement, never sent to the launcher.
 */
internal object NativeDockGlassPreviewGeometry {
    /** Hotseat LayoutBuilder height and its distance from the screen bottom, OS4 phone layout. */
    const val AREA_HEIGHT_DP = 102.4f
    const val AREA_BOTTOM_DP = 31.8f
    /** Cells of a full phone Hotseat row. */
    const val COLUMNS = 4
    /** Icons shown when the adaptive width is on, so the narrower glass is visible. */
    const val ADAPTIVE_SAMPLE_ICONS = 3

    fun iconCount(geometry: NativeDockGlassGeometry) = if (geometry.adaptive) ADAPTIVE_SAMPLE_ICONS else COLUMNS

    fun resolve(width: Int, height: Int, density: Float, geometry: NativeDockGlassGeometry): DockPreviewBounds? {
        if (width <= 0 || height <= 0 || !density.isFinite() || density <= 0f) return null
        val areaBottom = height - AREA_BOTTOM_DP * density
        // Same insets as the native factory: side margins, the bottom offset, and a top inset that
        // leaves the configured height; height 0 fills the area above the bottom offset.
        val available = (AREA_HEIGHT_DP - geometry.bottom).coerceAtLeast(0f) * density
        val panelHeight = (if (geometry.height == 0) available else min(geometry.height * density, available)).roundToInt()
        // Adaptive: one cell narrower per missing icon, half on each side, as the native factory.
        val missing = COLUMNS - iconCount(geometry)
        val margin = (geometry.effectiveMargin * density + missing * cell(width, density) / 2).roundToInt().coerceAtMost(width / 2)
        val panelWidth = width - 2 * margin
        if (panelWidth < 1 || panelHeight < 1) return null
        val bottom = (areaBottom - geometry.bottom * density).roundToInt()
        return DockPreviewBounds(margin, bottom - panelHeight, panelWidth, panelHeight,
            min(geometry.radius * density, min(panelWidth, panelHeight) / 2f))
    }

    /**
     * Measured launcher row: 68 dp icons, 49 dp from the screen bottom, 12 dp row inset. A row
     * with fewer icons keeps the cell width and stays centered.
     */
    fun icons(width: Int, height: Int, density: Float, count: Int = COLUMNS): List<DockPreviewBounds> {
        if (width <= 0 || height <= 0 || !density.isFinite() || density <= 0) return emptyList()
        val size = (68 * density).roundToInt().coerceIn(1, min(width, height))
        val cell = cell(width, density)
        val start = width / 2f - count * cell / 2
        val top = (height - 49 * density - size).roundToInt().coerceAtLeast(0)
        return List(count.coerceIn(0, COLUMNS)) { index ->
            DockPreviewBounds((start + (index + .5f) * cell - size / 2f).roundToInt(), top, size, size, 0f)
        }
    }

    private fun cell(width: Int, density: Float) = (width - 2 * (12 * density).coerceAtMost(width / 2f)) / COLUMNS
}
