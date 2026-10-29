package com.takekazex.hypertweak.presentation.launcher

import kotlin.math.min

/** Local illustration only. These dimensions must never be sent to the launcher as a host contract. */
internal object DesktopIconPreviewGeometry {
    data class Cell(val left: Float, val top: Float, val size: Float, val labelTop: Float)
    data class Layout(val cells: List<Cell>, val baselineSize: Float, val iconSize: Float, val cellWidth: Float)

    const val DEFAULT_PROGRESS = 250
    fun relativeScale(progress: Int): Float {
        require(progress in 0..500)
        return 0.5f + progress / 500f
    }

    fun resolve(width: Float, height: Float, columns: Int, rows: Int, progress: Int,
                textHeight: Float, margin: Float): Layout {
        require(width.isFinite() && width > 0 && height.isFinite() && height > 0)
        require(columns in 4..6 && rows in 5..8)
        require(textHeight.isFinite() && textHeight >= 0 && margin.isFinite() && margin >= 0)
        val scale = relativeScale(progress)
        val cellWidth = width / columns
        val cellHeight = height / rows
        // Reserve title space even at the largest icon setting and at large font sizes.
        val iconBudget = min(cellWidth - margin * 2, cellHeight - textHeight - margin * 2).coerceAtLeast(0f)
        val baseline = min(cellWidth * 0.52f, iconBudget / 1.5f)
        val icon = baseline * scale
        val cells = List(columns * rows) { index ->
            val left = index % columns * cellWidth + (cellWidth - icon) / 2
            val top = index / columns * cellHeight + (cellHeight - textHeight - margin - icon).coerceAtLeast(0f) / 2
            Cell(left, top, icon, top + icon + margin)
        }
        return Layout(cells, baseline, icon, cellWidth)
    }
}
