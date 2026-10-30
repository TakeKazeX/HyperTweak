package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelGlyphFrame
import com.takekazex.hypertweak.hook.rules.systemui.icon.SignalTargetOwnership
import com.takekazex.hypertweak.presentation.icon.duo.DuoDrawable
import com.takekazex.hypertweak.hook.rules.systemui.icon.StackedSignalTarget
import com.takekazex.hypertweak.hook.rules.systemui.icon.signalEndpointVisible
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalBars
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow
import com.takekazex.hypertweak.presentation.icon.duo.SignalBarMorph
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** One owner for a whole stacked slot, with independent per-subscription geometry inside it. */
internal class DuoStackedSignalMotion {
    private class Row {
        val source = PanelGlyphFrame(); val target = PanelGlyphFrame()
        val sourceLocal = RectF(); val targetLocal = RectF()
        val from = RectF(); val to = RectF()
        val renderer = SignalBarMorphRenderer()
        var geometry: SignalBarMorph? = null
        var sourceKey: Any? = null
        var geometryKey: Any? = null
    }
    private val rows = ArrayList<Row>()
    private var host: ViewGroup? = null
    private var endpoint: StackedSignalTarget? = null
    private var ownership: SignalTargetOwnership<View>? = null
    private var savedAlpha = 1f
    private var appliedAlpha = 1f
    private var ready = false
    private var failed = false
    private var reportedFailure = false
    private var progress = 0f
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val crop = RectF()
    private val pictureBounds = RectF()
    private val capturePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layer = object : Drawable() {
        override fun draw(canvas: Canvas) {
            if (failed || rows.isEmpty() || ready && progress >= 1f) return
            val save = canvas.saveLayerAlpha(null, if (ready) 255 else 0)
            try {
                rows.forEach { row ->
                    val geometry = row.geometry
                    if (geometry != null) row.renderer.draw(canvas, geometry, row.source, row.target,
                        row.sourceLocal, row.targetLocal, row.from, row.to, progress)
                    else {
                        if (!row.source.ink.isEmpty) drawFrame(canvas, row.source, row.sourceLocal, row.from, 1f - progress)
                        if (!row.target.ink.isEmpty) drawFrame(canvas, row.target, row.targetLocal, row.to, progress)
                    }
                }
                reportedFailure = false
                if (!ready) { ready = true; host?.postInvalidateOnAnimation() }
            } catch (error: Throwable) {
                // Do not remove an overlay while ViewOverlay is iterating its drawables.
                failed = true; ready = false
                endpoint?.view?.let { if (abs(it.alpha - appliedAlpha) < .001f) it.alpha = savedAlpha }
                if (!reportedFailure) com.takekazex.hypertweak.util.logging.DebugLog.w("DuoSignal", "stacked endpoint handoff failed", error)
                reportedFailure = true
            } finally { canvas.restoreToCount(save) }
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    private fun drawFrame(canvas: Canvas, frame: PanelGlyphFrame, local: RectF, world: RectF, opacity: Float) {
        if (local.isEmpty || world.isEmpty) return
        pictureBounds.set(world.left + (frame.ink.left - local.left) / local.width() * world.width(),
            world.top + (frame.ink.top - local.top) / local.height() * world.height(),
            world.left + (frame.ink.right - local.left) / local.width() * world.width(),
            world.top + (frame.ink.bottom - local.top) / local.height() * world.height())
        frame.draw(canvas, pictureBounds, (opacity * 255).toInt())
    }

    fun update(root: ViewGroup, source: View, icon: DuoDrawable, target: StackedSignalTarget,
               sourceMorphs: List<SignalBarMorph>, bars: DuoSignalBars?, fraction: Float,
               owners: SignalTargetOwnership<View>): Boolean {
        if (failed) { clear(); return false }
        val content = icon.content ?: run { clear(); return false }
        val dots = content.wifiLevel != null || icon.cellularDots
        val signals = if (dots) icon.signalDotRows() else content.cellularSignalRows
        val picture = if (dots) icon.signalDotsPicture() else icon.cellularSignalPicture
        if (picture == null || signals.isEmpty() || !root.isAttachedToWindow || !source.isAttachedToWindow ||
            !target.view.isAttachedToWindow || !signalEndpointVisible(target.view, root)) { clear(); return false }
        if (endpoint?.view !== target.view || host !== root) {
            clear()
            if (!owners.acquire(target.view, this)) return false
            ownership = owners
            endpoint = target; host = root; savedAlpha = target.view.alpha; appliedAlpha = savedAlpha
            root.overlay.add(layer)
        }
        val identityChanged = endpoint?.rows != target.rows || endpoint?.contours != target.contours || endpoint?.split != target.split
        endpoint = target
        if (identityChanged) rows.clear()
        if (abs(target.view.alpha - appliedAlpha) > .001f) savedAlpha = target.view.alpha
        matrix.reset(); root.transformMatrixToGlobal(matrix)
        if (!matrix.invert(inverse)) { clear(); return false }
        val mapping = DuoTransportSubscriptions.match(signals, target.rows, content.activeDataSubId)
        val count = signals.size + target.rows.indices.count { it !in mapping }
        while (rows.size < count) rows += Row()
        while (rows.size > count) rows.removeAt(rows.lastIndex)
        var position = 0
        val targetDirty = target.view.isDirty
        fun destination(row: Row, index: Int): Boolean {
            val view = target.view
            val drawable = view.drawable ?: return false
            row.targetLocal.set(drawable.bounds); view.imageMatrix.mapRect(row.targetLocal)
            row.targetLocal.offset(view.paddingLeft.toFloat(), view.paddingTop.toFloat())
            val top = row.targetLocal.top; val height = row.targetLocal.height()
            row.targetLocal.top = top + height * if (target.rows.size > 1 && index == 1) target.split else 0f
            row.targetLocal.bottom = top + height * if (target.rows.size > 1 && index == 0) target.split else 1f
            if (targetDirty) row.target.invalidateView()
            if (!row.target.captureView(view, row.targetLocal)) return false
            row.to.set(row.targetLocal); matrix.reset(); view.transformMatrixToGlobal(matrix)
            matrix.mapRect(row.to); inverse.mapRect(row.to)
            return true
        }
        signals.forEachIndexed { index, _ ->
            val row = rows[position++]
            val boundsReady = if (dots) icon.signalDotsBounds(crop, row.from, index) else icon.signalRowBounds(index, crop, row.from)
            if (!boundsReady) { clear(); return false }
            row.sourceLocal.set(crop)
            val sourceKey = listOf(picture, RectF(crop), icon.foreground)
            if (row.sourceKey != sourceKey) {
                row.sourceKey = sourceKey
                capturePaint.colorFilter = PorterDuffColorFilter(icon.foreground, PorterDuff.Mode.SRC_IN)
                if (!row.source.capture(picture.width, picture.height, crop) { canvas ->
                    val save = canvas.saveLayer(null, capturePaint)
                    try { canvas.drawPicture(picture) } finally { canvas.restoreToCount(save) }
                }) { clear(); return false }
            }
            matrix.reset(); source.transformMatrixToGlobal(matrix); matrix.mapRect(row.from); inverse.mapRect(row.from)
            val targetIndex = mapping[index]
            if (targetIndex >= 0) {
                if (!destination(row, targetIndex)) { clear(); return false }
                val dotMorph = if (dots) bars?.let { icon.signalDotMorph(index, it) } else null
                val sourceMorph = sourceMorphs.getOrNull(index)
                val geometryKey = listOf(dotMorph, sourceMorph, RectF(crop), target.contours[targetIndex])
                if (row.geometryKey != geometryKey) {
                    val sourceContours = if (dots) dotMorph?.dots?.map { dot ->
                        val cx = (dot.x - crop.left) / crop.width(); val cy = (dot.y - crop.top) / crop.height()
                        val rx = dot.radius / crop.width(); val ry = dot.radius / crop.height()
                        val path = Path().apply { addOval(cx-rx,cy-ry,cx+rx,cy+ry,Path.Direction.CW) }
                        SignalBarMorph.Contour(path, RectF(cx-rx,cy-ry,cx+rx,cy+ry), FloatArray(SignalBarMorph.SAMPLES * 2) { c ->
                            val angle = -PI / 2 + 2 * PI * (c / 2) / SignalBarMorph.SAMPLES
                            if (c % 2 == 0) cx + rx * cos(angle).toFloat() else cy + ry * sin(angle).toFloat()
                        })
                    } else sourceMorph?.source
                    if (sourceContours == null) { clear(); return false }
                    row.geometry = SignalBarMorph(sourceContours, target.contours[targetIndex])
                    row.geometryKey = geometryKey
                }
            } else { row.geometry = null; row.geometryKey = null; row.target.ink.setEmpty() }
        }
        target.rows.indices.filter { it !in mapping }.forEach { index ->
            val row = rows[position++]; row.source.ink.setEmpty(); row.geometry = null; row.geometryKey = null
            if (!destination(row, index)) { clear(); return false }
        }
        progress = fraction.coerceIn(0f, 1f)
        appliedAlpha = if (ready && progress < 1f) 0f else savedAlpha
        target.view.alpha = appliedAlpha
        layer.setBounds(0, 0, root.width, root.height); layer.invalidateSelf()
        return ready
    }
    fun clear() {
        endpoint?.view?.let { view ->
            if (abs(view.alpha - appliedAlpha) < .001f) view.alpha = savedAlpha
            ownership?.release(view, this)
        }
        host?.overlay?.remove(layer); host = null; endpoint = null; ownership = null; rows.clear(); ready = false; failed = false
    }
}
