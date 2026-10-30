package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.takekazex.hypertweak.presentation.icon.duo.DuoDotMorph
import com.takekazex.hypertweak.presentation.icon.duo.DuoDrawable
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalBars
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow
import com.takekazex.hypertweak.presentation.icon.duo.SignalBarMorph
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt

/** A subscription keeps one contour per bar/dot while moving between the center and lower gap. */
internal class DuoTransportSignalMotion {
    private val paths = Array(4) { Path() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crop = RectF()
    private val sourceBounds = RectF()
    private var cell: DuoDrawable? = null
    private var wifi: DuoDrawable? = null
    private var geometry: List<SignalBarMorph> = emptyList()
    private var sources: List<DuoSignalRow> = emptyList()
    private var destinations: List<DuoSignalRow> = emptyList()
    private var mapping: List<Int> = emptyList()
    private var centerDots: List<DuoDotMorph> = emptyList()
    private var lowerDots: List<DuoDotMorph> = emptyList()

    fun prepare(cell: DuoDrawable, wifi: DuoDrawable, geometry: List<SignalBarMorph>, bars: DuoSignalBars?): Boolean {
        val state = cell.content ?: return false
        if (bars == null || state.airplaneMode || state.cellularSignalLevels.isEmpty()) return false
        val rows = state.cellularSignalLevels.take(2)
        val source = state.cellularSignalRows.take(2).ifEmpty {
            rows.mapIndexed { i, level -> DuoSignalRow(-1 - i, i, level) }
        }
        if (source.size != rows.size || geometry.size != rows.size) return false
        val destination = wifi.signalDotRows()
        if (destination.isEmpty()) return false
        val lower = destination.indices.map { wifi.signalDotMorph(it, bars) ?: return false }
        val central = if (cell.cellularDots) source.indices.map { cell.signalDotMorph(it, bars) ?: return false } else emptyList()
        if (!cell.cellularDots && source.indices.any { !cell.signalRowBounds(it, crop, sourceBounds) }) return false
        this.cell = cell; this.wifi = wifi; this.geometry = geometry
        sources = source; destinations = destination; centerDots = central; lowerDots = lower
        mapping = DuoTransportSubscriptions.match(source, destination, state.activeDataSubId)
        return true
    }

    fun draw(canvas: Canvas, wifiFraction: Float) {
        val cell = cell ?: return
        val wifi = wifi ?: return
        val p = wifiFraction.coerceIn(0f, 1f)
        val side = min(cell.bounds.width(), cell.bounds.height()).toFloat()
        val scale = side / DuoDrawable.VIEWPORT
        val left = cell.bounds.left + (cell.bounds.width() - side) / 2f
        val top = cell.bounds.top + (cell.bounds.height() - side) / 2f
        sources.forEachIndexed { row, signal ->
            // Dual->single keeps the active subscription, rather than merging two rows into four dots.
            val destination = mapping[row]
            if (!cell.cellularDots) cell.signalRowBounds(row, crop, sourceBounds)
            val targetDots = lowerDots.getOrNull(destination)
            repeat(4) { bar ->
                val contour = geometry[row].source[bar]
                val startDot = centerDots.getOrNull(row)?.dots?.get(bar)
                val endDot = targetDots?.dots?.get(bar)
                val path = paths[bar].apply { rewind() }
                repeat(SignalBarMorph.SAMPLES) { sample ->
                    val dx = circle[sample * 2]; val dy = circle[sample * 2 + 1]
                    val ax = startDot?.let { left + (it.x + it.radius * dx) * scale }
                        ?: (sourceBounds.left + contour.points[sample * 2] * sourceBounds.width())
                    val ay = startDot?.let { top + (it.y + it.radius * dy) * scale }
                        ?: (sourceBounds.top + contour.points[sample * 2 + 1] * sourceBounds.height())
                    val bx = endDot?.let { left + (it.x + it.radius * dx) * scale } ?: ax
                    val by = endDot?.let { top + (it.y + it.radius * dy) * scale } ?: ay
                    val x = ax + (bx - ax) * p; val y = ay + (by - ay) * p
                    if (sample == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                val a = startDot?.alpha ?: if (bar < signal.level) 1f else DuoDrawable.SIGNAL_DOT_RESERVE
                val b = endDot?.alpha ?: 0f
                paint.color = cell.foreground
                val opacity = if (endDot == null) a * DuoTransportPhases.inactiveOpacity(p) else a + (b - a) * p
                paint.alpha = (Color.alpha(cell.foreground) * opacity).roundToInt().coerceIn(0, 255)
                canvas.drawPath(path, paint)
            }
        }
        // A newly reported subscription has no source contour; fade it in at its destination.
        destinations.forEachIndexed { index, _ ->
            if (index !in mapping) {
                lowerDots[index].dots.forEach { dot ->
                    paint.color = wifi.foreground
                    paint.alpha = (Color.alpha(wifi.foreground) * dot.alpha * p).roundToInt()
                    canvas.drawCircle(left + dot.x * scale, top + dot.y * scale, dot.radius * scale, paint)
                }
            }
        }
    }
    private companion object {
        val circle = FloatArray(SignalBarMorph.SAMPLES * 2).apply {
            repeat(SignalBarMorph.SAMPLES) { sample ->
                val angle = -PI / 2.0 + 2.0 * PI * sample / SignalBarMorph.SAMPLES
                this[sample * 2] = cos(angle).toFloat()
                this[sample * 2 + 1] = sin(angle).toFloat()
            }
        }
    }
}
