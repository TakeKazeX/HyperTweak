package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import com.takekazex.hypertweak.hook.rules.systemui.icon.AirplaneGlyphTargets
import com.takekazex.hypertweak.hook.rules.systemui.icon.GlyphMotionLease
import com.takekazex.hypertweak.hook.rules.systemui.icon.EndpointAlpha
import com.takekazex.hypertweak.hook.rules.systemui.icon.HandoverCoordinator

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Picture
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelGlyphFrame
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelGlyphTravel
import com.takekazex.hypertweak.hook.rules.systemui.icon.signalEndpointVisible
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlin.math.abs

/**
 * Carries the source artwork to a measured destination, owning its alpha until arrival.
 *
 * The destination stays hidden from the first motion frame, even before the overlay has drawn;
 * only the source remains until then, so no frame shows the glyph twice.
 */
internal class LeftPanelMotion(private val onFrameStateChanged: () -> Unit) {
    private var host: ViewGroup? = null
    private var source: View? = null
    private var target: View? = null
    private var savedTargetAlpha = 1f
    private var appliedTargetAlpha = 1f
    private var holdTargetUntilReady = false
    private var frameReady = false
    private var drawFailed = false
    private var progress = 0f
    private var sameArtwork = false
    private var sourcePicture: Picture? = null
    private var targetPicture: Picture? = null
    private var targetAlpha: EndpointAlpha? = null
    private var glyphLease: AirplaneGlyphTargets.Endpoint? = null
    private val targetRevoked: () -> Unit = { clear() }
    private var drawKey: List<Any?>? = null
    private val sourceFrame = PanelGlyphFrame()
    private val targetFrame = PanelGlyphFrame()
    private val travel = PanelGlyphTravel()
    private val from = RectF()
    private val to = RectF()
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val layer = object : Drawable() {
        override fun draw(canvas: Canvas) {
            if (source == null || drawFailed || frameReady && progress >= 1f) return
            val save = canvas.saveLayerAlpha(null, if (frameReady) 255 else 0)
            try {
                travel.draw(canvas, sourceFrame, targetFrame, from, to, progress, sameArtwork = sameArtwork)
                if (!frameReady) {
                    frameReady = true
                    val owner = host
                    owner?.post { if (host === owner) onFrameStateChanged() }
                    owner?.postInvalidateOnAnimation()
                }
            } catch (error: Throwable) {
                drawFailed = true; frameReady = false
                restoreTarget()
                if (sourcePicture == null) source?.let { HandoverCoordinator.alpha(it, 1f) }
                host?.post { onFrameStateChanged() }
                DebugLog.w("IconTuner", "left icon transition drawing failed", error)
            } finally { canvas.restoreToCount(save) }
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * [sourcePicture]/[targetPicture] replace a view capture for a glyph drawn inside that view, in
     * the view's own coordinates; [targetAlpha] then replaces the target view's `alpha`.
     */
    fun update(root: ViewGroup, source: View, target: View, progress: Float,
               parkedTargetAlpha: Float? = null, sameArtwork: Boolean = false, sourcePicture: Picture? = null,
               targetPicture: Picture? = null, targetAlpha: EndpointAlpha? = null,
               retainTargetUntilReady: Boolean = false): Boolean {
        if (!root.isAttachedToWindow || !source.isAttachedToWindow || !target.isAttachedToWindow ||
            root.width <= 0 || root.height <= 0 || source.width <= 0 || source.height <= 0 ||
            !signalEndpointVisible(target, root) || !progress.isFinite()) { clear(); return false }
        if (host !== root) { clear(); host = root; root.overlay.add(layer) }
        if (this.source !== source || (this.sourcePicture == null) != (sourcePicture == null)) {
            this.source = source; frameReady = false; drawFailed = false
            sourceFrame.invalidateView()
            this.sourcePicture = null
        }
        if (this.target !== target || this.targetAlpha !== targetAlpha) {
            restoreTarget()
            val endpoint = targetAlpha as? AirplaneGlyphTargets.Endpoint
            if (endpoint != null && !endpoint.motion.acquire(this, GlyphMotionLease.LEFT_OR_NATIVE, targetRevoked)) {
                clear(); return false
            }
            glyphLease = endpoint
            this.target = target; this.targetAlpha = targetAlpha
            savedTargetAlpha = parkedTargetAlpha ?: readTargetAlpha()
            appliedTargetAlpha = readTargetAlpha(); holdTargetUntilReady = parkedTargetAlpha != null
            frameReady = false
        } else if (parkedTargetAlpha != null) {
            savedTargetAlpha = parkedTargetAlpha; holdTargetUntilReady = true
        }
        if (abs(readTargetAlpha() - appliedTargetAlpha) > .001f) savedTargetAlpha = readTargetAlpha()
        val sourceReady = when {
            sourcePicture == null -> sourceFrame.captureView(source)
            this.sourcePicture === sourcePicture -> true
            else -> sourceFrame.capture(sourcePicture.width, sourcePicture.height) { it.drawPicture(sourcePicture) }
        }
        val targetReady = when {
            targetPicture == null -> targetFrame.captureView(target)
            this.targetPicture === targetPicture -> !targetFrame.ink.isEmpty
            else -> targetFrame.capture(targetPicture.width, targetPicture.height) { it.drawPicture(targetPicture) }
        }
        if (!sourceReady || !targetReady) { clear(); return false }
        this.sourcePicture = sourcePicture
        this.targetPicture = targetPicture
        matrix.reset(); root.transformMatrixToGlobal(matrix)
        if (!matrix.invert(inverse)) { clear(); return false }
        from.set(sourceFrame.ink); matrix.reset(); source.transformMatrixToGlobal(matrix); matrix.mapRect(from); inverse.mapRect(from)
        to.set(targetFrame.ink); matrix.reset(); target.transformMatrixToGlobal(matrix); matrix.mapRect(to); inverse.mapRect(to)
        this.progress = progress.coerceIn(0f, 1f)
        this.sameArtwork = sameArtwork
        val ready = frameReady && !drawFailed
        // One moving image through the full drag; do not reveal a stationary endpoint at 75%.
        // Before the first overlay frame the source is still drawn, so the destination waits hidden.
        appliedTargetAlpha = if (ready) savedTargetAlpha * LeftHandover.destinationAlpha(this.progress)
            else if (retainTargetUntilReady) savedTargetAlpha
            else if (holdTargetUntilReady || this.progress < 1f) 0f else savedTargetAlpha
        writeTargetAlpha(appliedTargetAlpha)
        layer.setBounds(0, 0, root.width, root.height)
        val nextKey = listOf(sourceFrame.revision, targetFrame.revision, RectF(from), RectF(to),
            this.progress, ready, sameArtwork, root.width, root.height)
        if (drawKey != nextKey) { drawKey = nextKey; layer.invalidateSelf() }
        return ready
    }
    private fun readTargetAlpha(): Float = targetAlpha?.value ?: target?.alpha ?: 1f
    private fun writeTargetAlpha(value: Float) {
        val alpha = targetAlpha
        if (alpha != null) alpha.value = value.coerceIn(0f, 1f) else target?.let { HandoverCoordinator.alpha(it, value) }
    }
    private fun restoreTarget() {
        if (target != null && abs(readTargetAlpha() - appliedTargetAlpha) < .001f) writeTargetAlpha(savedTargetAlpha)
        glyphLease?.motion?.release(this)
        glyphLease = null
        target = null; targetAlpha = null; targetPicture = null; holdTargetUntilReady = false
    }
    fun clear() {
        restoreTarget(); host?.overlay?.remove(layer)
        host = null; source = null; sourcePicture = null; frameReady = false; drawFailed = false
        drawKey = null
        from.setEmpty(); to.setEmpty()
    }
}
