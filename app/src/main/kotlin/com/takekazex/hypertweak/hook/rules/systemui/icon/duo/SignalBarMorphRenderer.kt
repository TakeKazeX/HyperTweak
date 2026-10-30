package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelGlyphFrame
import com.takekazex.hypertweak.presentation.icon.duo.SignalBarMorph

/** Each existing bar travels and changes outline independently, keeping the bound view's colors. */
internal class SignalBarMorphRenderer {
    private val paths = Array(4) { Path() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inkBounds = RectF()
    fun draw(canvas: Canvas, geometry: SignalBarMorph, source: PanelGlyphFrame, target: PanelGlyphFrame,
             sourceLocal: RectF, targetLocal: RectF, from: RectF, to: RectF, progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        if (p == 0f || p == 1f) {
            val frame = if (p == 0f) source else target
            val local = if (p == 0f) sourceLocal else targetLocal
            val world = if (p == 0f) from else to
            inkBounds.set(world.left + (frame.ink.left - local.left) / local.width() * world.width(),
                world.top + (frame.ink.top - local.top) / local.height() * world.height(),
                world.left + (frame.ink.right - local.left) / local.width() * world.width(),
                world.top + (frame.ink.bottom - local.top) / local.height() * world.height())
            frame.draw(canvas, inkBounds)
            return
        }
        repeat(4) { bar ->
            val a = geometry.source[bar]; val b = geometry.target[bar]
            val path = paths[bar].apply { rewind() }
            repeat(SignalBarMorph.SAMPLES) { i ->
                val ax = from.left + a.points[i * 2] * from.width()
                val ay = from.top + a.points[i * 2 + 1] * from.height()
                val bx = to.left + b.points[i * 2] * to.width()
                val by = to.top + b.points[i * 2 + 1] * to.height()
                val x = DuoPanelGeometry.mix(ax, bx, p); val y = DuoPanelGeometry.mix(ay, by, p)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            val sourceColor = source.colorAt(sourceLocal.left + a.bounds.centerX() * sourceLocal.width(),
                sourceLocal.top + a.bounds.centerY() * sourceLocal.height())
            val targetColor = target.colorAt(targetLocal.left + b.bounds.centerX() * targetLocal.width(),
                targetLocal.top + b.bounds.centerY() * targetLocal.height())
            paint.color = ColorUtils.blendARGB(sourceColor, targetColor, p)
            if (Color.alpha(paint.color) > 0) canvas.drawPath(path, paint)
        }
    }
}
