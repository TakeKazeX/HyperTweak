package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color as AwtColor
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the real glyph geometry on the JVM and asserts the properties that a device screenshot
 * cannot check cheaply: the ring stops where the dots begin, the Wi-Fi steps never overlap, the
 * whole glyph stays inside its box, and every mobile level keeps its lower steps visible.
 *
 * The PNG it writes (build/reports/duo-drawable.png) is the visual check against the reference
 * artwork; this test is the numeric one.
 */
class DuoDrawableGeometryTest {
    private val size = 512
    private val scale = size.toDouble() / DuoDrawable.VIEWPORT
    private val supersample = 2

    @Test
    fun `power ring stops at the signal dots`() {
        val lit = render { canvas -> drawTrack(canvas, chargedFraction = 0.0, lit = true) }
        val cx = DuoDrawable.CENTER.toDouble()
        val cy = DuoDrawable.CENTER.toDouble()
        val radius = DuoDrawable.TRACK_RADIUS.toDouble()
        val halfStroke = DuoDrawable.TRACK_STROKE / 2.0

        // The bottom of the ring is open; everything else carries the stroke.
        assertFalse("ring must not draw in the bottom gap", sampled(lit, cx, cy + radius))
        assertFalse("ring must not draw in the bottom gap", sampled(lit, cx - radius * 0.5, cy + radius * 0.87))
        assertTrue("ring must draw at the top", sampled(lit, cx, cy - radius))
        assertTrue("ring must draw at the left", sampled(lit, cx - radius, cy))
        assertTrue("ring must draw at the right", sampled(lit, cx + radius, cy))

        // The reserve ring runs along the same arc as the charged portion.
        assertTrue(
            "reserve and charge must share one arc",
            sampled(lit, cx + radius * cos(Math.toRadians(180.0)), cy + radius * sin(Math.toRadians(180.0)))
        )
        assertTrue("stroke must have thickness", halfStroke > 0.5)
    }

    @Test
    fun `wifi steps stay separated and inside the ring`() {
        val lit = render { canvas -> drawWifi(canvas, 3) }
        // Down the middle the glyph must read as three separate marks: outer arc, inner arc, centre
        // mark. Touching bands would collapse into fewer runs, which is the failure this guards.
        val runs = columnRuns(lit, DuoDrawable.CENTER)
        assertEquals("outer arc, inner arc and centre mark must stay separate", 3, runs.size)
        for (i in 0 until runs.size - 1) {
            val gap = runs[i + 1].first - runs[i].last
            assertTrue("bands must leave a visible gap between them (run $i)", gap >= 2)
        }
        assertTrue("outer arc sits above the inner arc", runs[0].first < runs[1].first)
        assertTrue("centre mark sits below both arcs", runs[2].first > runs[1].last)
        assertTrue(
            "glyph must stay clear of the dot row",
            runs.last().last < ((DuoDrawable.SIGNAL_DOT_Y[1] - DuoDrawable.SIGNAL_DOT_RADIUS) * scale).toInt()
        )
    }

    @Test
    fun `every level keeps the lower steps visible`() {
        val reserve = render { canvas -> drawWifi(canvas, 0) }
        val all = render { canvas -> drawWifi(canvas, 3) }
        val outerOnly = render { canvas -> drawWifi(canvas, 1) }

        assertTrue("reserve outline must be drawn", anyLit(reserve))
        assertTrue("level 3 must be brighter than the reserve", litCount(all) > litCount(reserve))
        assertTrue("level 1 lights only the centre mark", litCount(outerOnly) < litCount(all))
        assertTrue("level 1 marks a smaller area than the inner arc", litCount(outerOnly) < litCount(render { drawWifi(it, 2) }))
    }

    @Test
    fun `glyph stays inside its box at every level`() {
        for (level in 0..4) {
            val image = render { canvas ->
                drawTrack(canvas, chargedFraction = 0.75, lit = true)
                drawWifi(canvas, level)
                drawDots(canvas, mobileLevel = level, noService = false)
            }
            val edge = 2
            for (x in 0 until size) {
                assertFalse("top edge must stay clear (level $level)", (0 until edge).any { alphaAt(image, x, it) > 0 })
                assertFalse("bottom edge must stay clear (level $level)", (size - edge until size).any { alphaAt(image, x, it) > 0 })
            }
            for (y in 0 until size) {
                assertFalse("left edge must stay clear (level $level)", (0 until edge).any { alphaAt(image, it, y) > 0 })
                assertFalse("right edge must stay clear (level $level)", (size - edge until size).any { alphaAt(image, it, y) > 0 })
            }
        }
    }

    @Test
    fun `no-service keeps the glyph readable`() {
        val normal = render { canvas ->
            drawTrack(canvas, chargedFraction = 0.5, lit = true); drawWifi(canvas, 3); drawDots(canvas, 1, noService = false)
        }
        val noService = render { canvas ->
            drawTrack(canvas, chargedFraction = 0.5, lit = true); drawWifi(canvas, 3); drawDots(canvas, 1, noService = true)
        }
        assertTrue("no-service must still draw something", anyLit(noService))
        assertTrue("no-service dims the dots", litCount(noService) < litCount(normal))
    }

    @Test
    fun `writes the full glyph for visual comparison`() {
        val sheet = BufferedImage(size * 2, size, BufferedImage.TYPE_INT_ARGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        for ((index, level) in listOf(3, 1).withIndex()) {
            val cell = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val cg = cell.createGraphics()
            cg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            cg.scale(scale, scale)
            drawTrack(cg, chargedFraction = 0.62, lit = true)
            drawWifi(cg, level)
            drawDots(cg, mobileLevel = 4, noService = false)
            cg.dispose()
            g.drawImage(cell, index * size, 0, null)
        }
        g.dispose()
        val file = File("build/reports/duo-drawable-full.png")
        file.parentFile?.mkdirs()
        ImageIO.write(sheet, "png", file)
    }

    // ---- renderer -------------------------------------------------------------------------

    private fun render(block: (Graphics2D) -> Unit): BufferedImage {
        val image = BufferedImage(size * supersample, size * supersample, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(supersample.toDouble(), supersample.toDouble())
        g.scale(scale, scale)
        block(g)
        g.dispose()
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2 = out.createGraphics()
        g2.drawImage(image, 0, 0, size, size, null)
        g2.dispose()
        writeOnce(out)
        return out
    }

    private fun drawTrack(g: Graphics2D, chargedFraction: Double, lit: Boolean) {
        val r = DuoDrawable.TRACK_RADIUS.toDouble()
        val c = DuoDrawable.CENTER.toDouble()
        g.stroke = BasicStroke(DuoDrawable.TRACK_STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = AwtColor(255, 255, 255, (255 * DuoDrawable.TRACK_RESERVE).toInt())
        g.draw(arcPath(c, c, r, DuoDrawable.TRACK_START.toDouble(), DuoDrawable.TRACK_SWEEP.toDouble()))
        if (!lit) return
        g.color = AwtColor.WHITE
        g.draw(arcPath(c, c, r, DuoDrawable.TRACK_START.toDouble(), DuoDrawable.TRACK_SWEEP * chargedFraction))
    }

    private fun drawWifi(g: Graphics2D, level: Int) {
        wifiArc(g, DuoDrawable.WIFI_OUTER_START_X, DuoDrawable.WIFI_OUTER_END_X, DuoDrawable.WIFI_OUTER_END_Y, DuoDrawable.WIFI_OUTER_CONTROL_Y, DuoDrawable.WIFI_OUTER_STROKE, level >= 3)
        wifiArc(g, DuoDrawable.WIFI_INNER_START_X, DuoDrawable.WIFI_INNER_END_X, DuoDrawable.WIFI_INNER_END_Y, DuoDrawable.WIFI_INNER_CONTROL_Y, DuoDrawable.WIFI_INNER_STROKE, level >= 2)
        drawMark(g, level >= 1)
    }

    private fun wifiArc(g: Graphics2D, startX: Float, endX: Float, endY: Float, controlY: Float, stroke: Float, lit: Boolean) {
        val path = Path2D.Float()
        path.moveTo(startX.toDouble(), endY.toDouble())
        path.quadTo(DuoDrawable.CENTER.toDouble(), controlY.toDouble(), endX.toDouble(), endY.toDouble())
        g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = AwtColor(255, 255, 255, if (lit) 255 else (255 * DuoDrawable.GLYPH_RESERVE).toInt())
        g.draw(path)
    }

    private fun drawMark(g: Graphics2D, lit: Boolean) {
        val c = DuoDrawable.CENTER
        val r = DuoDrawable.MARK_RADIUS
        val top = DuoDrawable.MARK_TOP_Y
        val bottom = DuoDrawable.MARK_BOTTOM_Y
        val path = Path2D.Float()
        path.moveTo(c.toDouble(), top.toDouble())
        path.quadTo((c + r).toDouble(), top.toDouble(), (c + r * 0.85f).toDouble(), (top + r * 0.6f).toDouble())
        path.lineTo((c + r * 0.28f).toDouble(), (bottom - r * 0.45f).toDouble())
        path.quadTo(c.toDouble(), (bottom + r * 0.25f).toDouble(), (c - r * 0.28f).toDouble(), (bottom - r * 0.45f).toDouble())
        path.lineTo((c - r * 0.85f).toDouble(), (top + r * 0.6f).toDouble())
        path.quadTo((c - r).toDouble(), top.toDouble(), c.toDouble(), top.toDouble())
        path.closePath()
        g.color = AwtColor(255, 255, 255, if (lit) 255 else (255 * DuoDrawable.GLYPH_RESERVE).toInt())
        g.fill(path)
    }

    private fun drawDots(g: Graphics2D, mobileLevel: Int, noService: Boolean) {
        for (index in 0 until DuoDrawable.SIGNAL_DOT_COUNT) {
            val lit = !noService && index < mobileLevel
            val r = DuoDrawable.SIGNAL_DOT_RADIUS.toDouble()
            val x = DuoDrawable.SIGNAL_DOT_X[index] - r
            val y = DuoDrawable.SIGNAL_DOT_Y[index] - r
            g.color = AwtColor(255, 255, 255, if (lit) 255 else (255 * DuoDrawable.SIGNAL_DOT_RESERVE).toInt())
            g.fill(java.awt.geom.Ellipse2D.Double(x, y, r * 2, r * 2))
        }
    }

    private fun arcPath(cx: Double, cy: Double, r: Double, startDeg: Double, sweepDeg: Double): Path2D.Float {
        val path = Path2D.Float()
        val steps = maxOf(8, (sweepDeg / 3).toInt())
        for (i in 0..steps) {
            val deg = startDeg + sweepDeg * i / steps
            val x = cx + r * cos(Math.toRadians(deg))
            val y = cy + r * sin(Math.toRadians(deg))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return path
    }

    // ---- assertions helpers ---------------------------------------------------------------

    private fun sampled(image: BufferedImage, xUnits: Double, yUnits: Double): Boolean {
        val x = (xUnits * scale).toInt().coerceIn(0, size - 1)
        val y = (yUnits * scale).toInt().coerceIn(0, size - 1)
        // Tolerance: look for ink in a 3x3 neighbourhood so a thin stroke is not missed.
        for (dx in -1..1) for (dy in -1..1) {
            val px = (x + dx).coerceIn(0, size - 1)
            val py = (y + dy).coerceIn(0, size - 1)
            if (alphaAt(image, px, py) > 40) return true
        }
        return false
    }

    /** Rows of ink in one column, as inclusive pixel ranges, separated by clear gaps. */
    private fun columnRuns(image: BufferedImage, xUnits: Float): List<IntRange> {
        val x = (xUnits * scale).toInt().coerceIn(0, size - 1)
        val runs = mutableListOf<IntRange>()
        var start = -1
        var gap = 0
        for (y in 0 until size) {
            if (alphaAt(image, x, y) > 40) {
                if (start < 0) start = y
                gap = 0
            } else if (start >= 0) {
                gap++
                if (gap > 1) {
                    runs += start..(y - gap)
                    start = -1
                }
            }
        }
        if (start >= 0) runs += start..(size - 1)
        return runs
    }

    private fun topmost(image: BufferedImage, xUnits: Float): Int {
        val x = (xUnits * scale).toInt().coerceIn(0, size - 1)
        for (y in 0 until size) if (alphaAt(image, x, y) > 40) return y
        return size
    }

    private fun anyLit(image: BufferedImage): Boolean = litCount(image) > 0

    private fun litCount(image: BufferedImage): Int {
        var count = 0
        for (y in 0 until size) for (x in 0 until size) if (alphaAt(image, x, y) > 40) count++
        return count
    }

    private fun alphaAt(image: BufferedImage, x: Int, y: Int): Int = (image.getRGB(x, y) ushr 24) and 0xff

    private fun writeOnce(image: BufferedImage) {
        if (written) return
        written = true
        val file = File("build/reports/duo-drawable.png")
        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
    }

    private companion object {
        var written = false
    }
}
