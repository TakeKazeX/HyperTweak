package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoPanelGeometry
import com.takekazex.hypertweak.util.DebugLog
import kotlin.math.abs

/**
 * Carries one left-placed StatusBarIconView through the shade hand-over.
 *
 * This deliberately follows [com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoPanelMotion]:
 * the host's real expansion fraction is the only clock, the intermediate image lives in a
 * ViewGroupOverlay, and the native endpoint is revealed only after the overlay has drawn. The
 * source view is drawn directly so its StatusBarIconView tint/scale/drawable pipeline is retained;
 * no bitmap snapshot or second host child is introduced.
 */
internal class LeftPanelMotion {
    private var host: ViewGroup? = null
    private var source: View? = null
    private var target: View? = null
    private var savedTargetAlpha = 1f
    private var appliedTargetAlpha = 1f
    private var holdTargetUntilReady = false
    private val location = IntArray(2)
    private var centerX = 0f
    private var centerY = 0f
    private var drawWidth = 0f
    private var drawHeight = 0f
    private var layerAlpha = 255
    private var frameReady = false
    private var drawFailed = false

    private val layer = object : Drawable() {
        override fun draw(canvas: Canvas) {
            val view = source
            if (view == null || drawFailed || layerAlpha <= 0 || drawWidth <= 0f || drawHeight <= 0f ||
                view.width <= 0 || view.height <= 0
            ) return

            val outerSave = canvas.save()
            try {
                canvas.translate(centerX - drawWidth / 2f, centerY - drawHeight / 2f)
                canvas.scale(drawWidth / view.width, drawHeight / view.height)
                if (layerAlpha >= 255) {
                    view.draw(canvas)
                } else {
                    @Suppress("DEPRECATION")
                    val alphaSave = canvas.saveLayerAlpha(
                        0f, 0f, view.width.toFloat(), view.height.toFloat(), layerAlpha
                    )
                    view.draw(canvas)
                    canvas.restoreToCount(alphaSave)
                }
                if (!frameReady) {
                    frameReady = true
                    host?.postInvalidateOnAnimation()
                }
            } catch (error: Throwable) {
                drawFailed = true
                frameReady = false
                restoreTarget()
                host?.postInvalidateOnAnimation()
                DebugLog.w("IconTuner", "left icon transition drawing failed", error)
            } finally {
                canvas.restoreToCount(outerSave)
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * Updates the overlay from the left clone to the selected real control-center endpoint.
     * Returns true only after one complete overlay draw, matching DuoPanelMotion's fail-open rule.
     */
    fun update(
        root: ViewGroup,
        source: View,
        target: View,
        progress: Float,
        parkedTargetAlpha: Float? = null
    ): Boolean {
        if (!root.isAttachedToWindow || !source.isAttachedToWindow || !target.isAttachedToWindow ||
            root.width <= 0 || root.height <= 0 || source.width <= 0 || source.height <= 0 ||
            target.width <= 0 || target.height <= 0
        ) {
            clear()
            return false
        }

        if (host !== root) {
            clear()
            runCatching {
                root.overlay.add(layer)
                host = root
            }.onFailure {
                drawFailed = true
                DebugLog.w("IconTuner", "left icon transition overlay unavailable", it)
                return false
            }
        }
        if (this.source !== source) {
            this.source = source
            frameReady = false
            drawFailed = false
        }
        if (this.target !== target) {
            restoreTarget()
            this.target = target
            savedTargetAlpha = parkedTargetAlpha ?: target.alpha
            appliedTargetAlpha = target.alpha
            holdTargetUntilReady = parkedTargetAlpha != null
        } else if (parkedTargetAlpha != null) {
            // A target parked by the closed-but-still-visible panel must stay hidden until this
            // overlay has drawn once. The saved value is the host value to restore at the endpoint.
            savedTargetAlpha = parkedTargetAlpha
            holdTargetUntilReady = true
        }
        if (abs(target.alpha - appliedTargetAlpha) > .001f) {
            // The host changed the endpoint while we owned it; start from that new value and do not
            // restore an obsolete alpha when the hand-over is released.
            savedTargetAlpha = target.alpha
        }

        val clamped = progress.coerceIn(0f, 1f)
        val ready = frameReady && !drawFailed
        appliedTargetAlpha = if (ready) {
            savedTargetAlpha * DuoPanelGeometry.handoff(clamped)
        } else if (holdTargetUntilReady) {
            0f
        } else {
            savedTargetAlpha
        }
        target.alpha = appliedTargetAlpha

        centerOnScreen(source)
        val sourceX = location[0] + source.width / 2f
        val sourceY = location[1] + source.height / 2f
        centerOnScreen(target)
        val targetX = location[0] + target.width / 2f
        val targetY = location[1] + target.height / 2f
        root.getLocationOnScreen(location)
        centerX = DuoPanelGeometry.mix(sourceX, targetX, clamped) - location[0]
        centerY = DuoPanelGeometry.mix(sourceY, targetY, clamped) - location[1]
        drawWidth = DuoPanelGeometry.mix(source.width.toFloat(), target.width.toFloat(), clamped)
        drawHeight = DuoPanelGeometry.mix(source.height.toFloat(), target.height.toFloat(), clamped)
        layerAlpha = DuoPanelGeometry.overlayAlpha(clamped)
        layer.setBounds(0, 0, root.width, root.height)
        // The source view may have received a new StatusBarIcon payload without changing geometry.
        // Invalidate every host progress update so the overlay always draws the current glyph.
        layer.invalidateSelf()
        return ready
    }

    private fun centerOnScreen(view: View) {
        view.getLocationOnScreen(location)
    }

    private fun restoreTarget() {
        target?.let { endpoint ->
            if (abs(endpoint.alpha - appliedTargetAlpha) < .001f) endpoint.alpha = savedTargetAlpha
        }
        target = null
        holdTargetUntilReady = false
    }

    fun clear() {
        restoreTarget()
        host?.overlay?.remove(layer)
        host = null
        source = null
        holdTargetUntilReady = false
        frameReady = false
        drawFailed = false
        centerX = 0f
        centerY = 0f
        drawWidth = 0f
        drawHeight = 0f
        layerAlpha = 255
    }
}
