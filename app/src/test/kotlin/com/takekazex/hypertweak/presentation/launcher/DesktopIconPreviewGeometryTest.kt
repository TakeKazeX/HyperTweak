package com.takekazex.hypertweak.presentation.launcher

import org.junit.Assert.*
import org.junit.Test

class DesktopIconPreviewGeometryTest {
    @Test fun allGridCombinationsKeepIconsAndTitlesInsideTheirCells() {
        for (columns in 4..6) for (rows in 5..8) for (width in listOf(240f, 360f, 600f)) {
            val height = width / 0.68f
            for (fontHeight in listOf(12f, 24f, 36f)) for (progress in listOf(0, 250, 500)) {
                val layout = DesktopIconPreviewGeometry.resolve(width, height, columns, rows, progress, fontHeight, 4f)
                assertEquals(columns * rows, layout.cells.size)
                layout.cells.forEachIndexed { index, cell ->
                    val cellLeft = index % columns * width / columns
                    val cellTop = index / columns * height / rows
                    assertTrue(cell.left >= cellLeft)
                    assertTrue(cell.left + cell.size <= cellLeft + width / columns + 0.001f)
                    assertTrue(cell.top >= cellTop)
                    assertTrue(cell.labelTop >= cell.top + cell.size)
                    assertTrue(cell.labelTop + fontHeight <= cellTop + height / rows + 0.001f)
                }
            }
        }
    }

    @Test fun scalingChangesIconsWhileKeepingReferenceAndCellWidthsStable() {
        val small = DesktopIconPreviewGeometry.resolve(360f, 530f, 4, 6, 0, 16f, 4f)
        val normal = DesktopIconPreviewGeometry.resolve(360f, 530f, 4, 6, 250, 16f, 4f)
        val large = DesktopIconPreviewGeometry.resolve(360f, 530f, 4, 6, 500, 16f, 4f)
        assertEquals(small.baselineSize, large.baselineSize, 0f)
        assertEquals(small.cellWidth, large.cellWidth, 0f)
        assertEquals(normal.iconSize * 0.5f, small.iconSize, 0.001f)
        assertEquals(normal.iconSize * 1.5f, large.iconSize, 0.001f)
    }

    @Test fun invalidDraftAndDimensionsAreRejected() {
        for (bad in listOf(-1, 501)) assertThrows(IllegalArgumentException::class.java) {
            DesktopIconPreviewGeometry.relativeScale(bad)
        }
        for (bad in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY)) assertThrows(IllegalArgumentException::class.java) {
            DesktopIconPreviewGeometry.resolve(bad, 530f, 4, 6, 250, 16f, 4f)
        }
    }
}
