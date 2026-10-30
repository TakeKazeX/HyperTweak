package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import com.takekazex.hypertweak.presentation.icon.duo.DuoDrawable
import com.takekazex.hypertweak.presentation.icon.duo.DuoRenderLayer
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlin.math.min
import kotlin.math.roundToInt

/** One moving power arc; HOME and the expanded battery are distinct drawing endpoints. */
internal class DuoPowerMotion(context: Context) {
    private val ring = DuoDrawable(context, DuoRenderLayer.POWER_TRACK).apply { hideTopIndicators = true }
    private val battery = DuoDrawable(context).apply { batteryOnly = true; hidePowerTrack = true }
    private val sourceRect = RectF()
    private val proxyRect = RectF()
    private val targetRect = RectF()
    private val header = RectF()
    private val percent = RectF()
    private val matrix = Matrix()
    private val inverseRoot = Matrix()
    private val vector = FloatArray(2)
    private var root: ViewGroup? = null
    private var source: DuoIconView? = null
    private var proxy: DuoIconView? = null
    private var target: DuoIconView? = null
    private var ready = false
    private var failed = false
    private var progress = 0f
    private var side = 0f
    private var left = 0f
    private var top = 0f
    private var textX = 0f
    private var textY = 0f
    private var textHeight = 0f
    private var hasHeader = false
    private var hasPercent = false

    private val layer = object : Drawable() {
        override fun draw(canvas: Canvas) = drawFrame(canvas)
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun drawFrame(canvas: Canvas) {
        if (failed || side <= 0f) return
        try {
            val save = canvas.save()
            try {
                canvas.translate(left, top)
                canvas.scale(side / DuoDrawable.VIEWPORT, side / DuoDrawable.VIEWPORT)
                ring.draw(canvas)
                if (progress > .75f) battery.draw(canvas)
            } finally { canvas.restoreToCount(save) }
            val handoff = DuoPanelGeometry.handoff(progress)
            if (hasHeader) drawTextPart(canvas, header, textHeight / header.height(),
                ((1f - handoff) * 255f).roundToInt()) { ring.drawTopIndicators(it) }
            if (hasPercent) drawTextPart(canvas, percent, textHeight / percent.height(),
                ((if (hasHeader) handoff else progress) * 255f).roundToInt()) { target?.drawBelowPercent(it) }
            if (!ready) { ready = true; root?.postInvalidateOnAnimation() }
        } catch (error: Throwable) {
            failed = true
            releaseViews()
            root?.postInvalidateOnAnimation()
            DebugLog.w("DuoSignal", "power handover drawing failed", error)
        }
    }


    private inline fun drawTextPart(canvas: Canvas, bounds: RectF, scale: Float, alpha: Int,
                                    draw: (Canvas) -> Unit) {
        if (alpha <= 0 || !scale.isFinite() || scale <= 0f) return
        val save = canvas.saveLayerAlpha(null, alpha)
        try {
            canvas.translate(textX, textY)
            canvas.scale(scale, scale)
            canvas.translate(-bounds.centerX(), -bounds.centerY())
            draw(canvas)
        } finally { canvas.restoreToCount(save) }
    }

    private fun glyphRect(view: DuoIconView, out: RectF) {
        val side = view.drawnIconSidePx().toFloat()
        val x = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) view.paddingLeft.toFloat()
            else view.width - view.paddingRight - side
        val y = ((view.height - side.toInt()) / 2).toFloat()
        out.set(x, y, x + side, y + side)
        matrix.reset()
        view.transformMatrixToGlobal(matrix)
        matrix.postConcat(inverseRoot)
        matrix.mapRect(out)
    }

    fun update(host: ViewGroup, home: DuoIconView, copy: DuoIconView, expanded: DuoIconView,
               expandedRoot: View, fraction: Float): Boolean {
        if (!fraction.isFinite() || !host.isAttachedToWindow || !home.isAttachedToWindow ||
            !expanded.isAttachedToWindow || !expanded.icon.batteryOnly ||
            home.drawnIconSidePx() <= 0 || expanded.drawnIconSidePx() <= 0 ||
            home.icon.content == null || expanded.icon.innerBatteryDrawable == null) {
            clear(); return false
        }
        if (root !== host || source !== home || proxy !== copy || target !== expanded) {
            clear(); root = host; source = home; proxy = copy; target = expanded; host.overlay.add(layer)
        }
        if (failed) return false
        matrix.reset(); host.transformMatrixToGlobal(matrix)
        if (!matrix.invert(inverseRoot)) { clear(); return false }
        glyphRect(home, sourceRect); glyphRect(copy, proxyRect); glyphRect(expanded, targetRect)
        // Match positionProxy's settled endpoint, removing the native header's temporary motion.
        matrix.reset()
        (expandedRoot.parent as? View)?.transformMatrixToGlobal(matrix)
        matrix.postConcat(inverseRoot)
        vector[0] = expandedRoot.translationX; vector[1] = expandedRoot.translationY
        matrix.mapVectors(vector)
        targetRect.offset(-vector[0], -vector[1])
        prepareFrame(home, copy, expanded, sourceRect, proxyRect, targetRect, fraction)
        layer.setBounds(0, 0, host.width, host.height)
        layer.invalidateSelf()
        return ready
    }

    /** Geometry and drawing are separable from attachment, so actual Canvas endpoints can be checked offscreen. */
    fun prepareFrame(home: DuoIconView, copy: DuoIconView, expanded: DuoIconView,
                     from: RectF, current: RectF, to: RectF, fraction: Float) {
        source = home; proxy = copy; target = expanded
        sourceRect.set(from); proxyRect.set(current); targetRect.set(to)
        progress = fraction.coerceIn(0f, 1f)
        val sourceSide = min(sourceRect.width(), sourceRect.height())
        val targetSide = min(targetRect.width(), targetRect.height())
        side = DuoPanelGeometry.mix(sourceSide, targetSide, progress)
        val rtl = copy.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val sourceEnd = if (rtl) sourceRect.left else sourceRect.right
        val targetEnd = if (rtl) targetRect.left else targetRect.right
        val proxyEnd = if (rtl) proxyRect.left else proxyRect.right
        left = if (rtl) proxyEnd else proxyEnd - side
        top = proxyRect.centerY() - side / 2f
        ring.content = home.icon.content
        ring.showTopPercent = home.icon.showTopPercent
        ring.topIndicatorsEnabled = home.icon.topIndicatorsEnabled
        ring.topGapProgress = progress
        ring.sizes = home.icon.sizes.copy(ring = DuoPanelGeometry.mix(home.icon.sizes.ring, expanded.icon.sizes.ring, progress))
        ring.foreground = ColorUtils.blendARGB(home.icon.foreground, expanded.icon.foreground, progress)
        ring.setBounds(0, 0, DuoDrawable.VIEWPORT.toInt(), DuoDrawable.VIEWPORT.toInt())
        battery.content = expanded.icon.content
        battery.sizes = expanded.icon.sizes
        battery.foreground = expanded.icon.foreground
        battery.innerBatteryDrawable = expanded.icon.innerBatteryDrawable
        battery.alpha = (DuoPanelGeometry.handoff(progress) * 255f).roundToInt()
        battery.bounds = ring.bounds
        hasHeader = home.icon.topIndicatorsEnabled && ring.topIndicatorViewportBounds(header)
        hasPercent = expanded.belowPercentBounds(percent)
        val sourceScale = sourceSide / DuoDrawable.VIEWPORT
        val targetScale = targetSide / expanded.drawnIconSidePx()
        val startX = sourceRect.left + (if (hasHeader) header.centerX() else DuoDrawable.CENTER) * sourceScale
        val startY = sourceRect.top + (if (hasHeader) header.centerY() else DuoDrawable.VIEWPORT) * sourceScale
        val endX = targetRect.left + (if (hasPercent) percent.centerX() else expanded.drawnIconSidePx() / 2f) * targetScale
        val endY = targetRect.top + (if (hasPercent) percent.centerY() else expanded.drawnIconSidePx().toFloat()) * targetScale
        textX = DuoPanelGeometry.mix(startX, endX, progress) +
            proxyEnd - DuoPanelGeometry.mix(sourceEnd, targetEnd, progress)
        textY = DuoPanelGeometry.mix(startY, endY, progress) +
            proxyRect.centerY() - DuoPanelGeometry.mix(sourceRect.centerY(), targetRect.centerY(), progress)
        val sourceHeight = if (hasHeader) header.height() * sourceScale else 0f
        textHeight = DuoPanelGeometry.mix(sourceHeight,
            if (hasPercent) percent.height() * targetScale else sourceHeight, progress)
        home.powerInMotion = ready
        copy.powerInMotion = ready
        expanded.powerInMotion = ready
    }

    private fun releaseViews() {
        source?.powerInMotion = false
        proxy?.powerInMotion = false
        target?.powerInMotion = false
    }
    fun clear() {
        releaseViews()
        root?.overlay?.remove(layer)
        root = null; source = null; proxy = null; target = null
        ready = false; failed = false; side = 0f
    }
}
