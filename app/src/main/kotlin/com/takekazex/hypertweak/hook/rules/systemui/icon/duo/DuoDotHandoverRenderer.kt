package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RectF
import com.takekazex.hypertweak.presentation.icon.duo.DuoDotMorph
import com.takekazex.hypertweak.presentation.icon.duo.DuoDrawable
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalBars
import kotlin.math.min
import kotlin.math.roundToInt

/** Four independent contours: each circle becomes its corresponding SVG bar, never a second image. */
internal class DuoDotHandoverRenderer {
    private val paths = Array(4) { Path() }
    private val values = FloatArray(6)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val fallbackBounds = RectF()

    fun drawMorph(canvas: Canvas, morph: DuoDotMorph, crop: RectF, from: RectF, to: RectF, progress: Float) {
        if (crop.isEmpty || from.isEmpty || to.isEmpty || !progress.isFinite()) return
        val p = progress.coerceIn(0f, 1f)
        val sourceScale = min(from.width() / crop.width(), from.height() / crop.height())
        val sourceLeft = from.centerX() - crop.width() * sourceScale / 2f
        val sourceTop = from.centerY() - crop.height() * sourceScale / 2f
        val target = morph.target
        val sx = to.width() / target.width
        val sy = to.height() / target.height
        morph.dots.forEachIndexed { index, dot ->
            val cx = sourceLeft + (dot.x - crop.left) * sourceScale
            val cy = sourceTop + (dot.y - crop.top) * sourceScale
            val radius = dot.radius * sourceScale
            val path = paths[index].apply { rewind() }
            target.bars[index].forEachIndexed { nodeIndex, node ->
                val circle = DuoSignalBars.circle[nodeIndex]
                node.params.forEachIndexed { coordinate, value ->
                    val xAxis = node.type == 'H' || node.type != 'V' && coordinate % 2 == 0
                    val a = (if (xAxis) cx else cy) + circle[coordinate] * radius
                    val b = if (xAxis) to.left + (value - target.left) * sx
                        else to.top + (value - target.top) * sy
                    values[coordinate] = DuoPanelGeometry.mix(a, b, p)
                }
                when (node.type) {
                    'M' -> path.moveTo(values[0], values[1])
                    'C' -> path.cubicTo(values[0], values[1], values[2], values[3], values[4], values[5])
                    'H' -> path.lineTo(values[0], pathY(nodeIndex, morph, index, cy, radius, to, sy, p))
                    'V' -> path.lineTo(pathX(nodeIndex, morph, index, cx, radius, to, sx, p), values[0])
                    'Z' -> path.close()
                }
            }
            val targetAlpha = if (index < morph.level) 1f else DuoDrawable.SIGNAL_DOT_RESERVE
            paint.alpha = (DuoPanelGeometry.mix(dot.alpha, targetAlpha, p) * 255f).roundToInt()
            canvas.drawPath(path, paint)
        }
    }

    // H/V preserve the other coordinate of the preceding cubic endpoint. Its source counterpart
    // is the exact circle quadrant endpoint, so collapsed straight edges grow continuously.
    private fun pathY(node: Int, morph: DuoDotMorph, bar: Int, cy: Float, r: Float,
                      to: RectF, scale: Float, p: Float): Float {
        val previous = morph.target.bars[bar][node - 1].params
        val source = DuoSignalBars.circle[node - 1]
        return DuoPanelGeometry.mix(cy + source.last() * r,
            to.top + (previous.last() - morph.target.top) * scale, p)
    }
    private fun pathX(node: Int, morph: DuoDotMorph, bar: Int, cx: Float, r: Float,
                      to: RectF, scale: Float, p: Float): Float {
        val previous = morph.target.bars[bar][node - 1].params
        val source = DuoSignalBars.circle[node - 1]
        return DuoPanelGeometry.mix(cx + source[source.size - 2] * r,
            to.left + (previous[previous.size - 2] - morph.target.left) * scale, p)
    }

    /** Missing/changed SVG contracts retain circular dots until native target ownership resumes. */
    fun drawFallback(canvas: Canvas, source: Picture, crop: RectF, destination: RectF) {
        if (crop.isEmpty || destination.isEmpty) return
        val scale = min(destination.width() / crop.width(), destination.height() / crop.height())
        val halfWidth = crop.width() * scale / 2f
        val halfHeight = crop.height() * scale / 2f
        fallbackBounds.set(destination.centerX() - halfWidth, destination.centerY() - halfHeight,
            destination.centerX() + halfWidth, destination.centerY() + halfHeight)
        val save = canvas.save()
        try {
            canvas.clipRect(fallbackBounds)
            canvas.translate(fallbackBounds.left, fallbackBounds.top)
            canvas.scale(scale, scale)
            canvas.translate(-crop.left, -crop.top)
            canvas.drawPicture(source)
        } finally { canvas.restoreToCount(save) }
    }
}
