package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.util.DebugLog

/** Travel follows expansion; visibility follows the native fake-row appearance animation. */
internal object CarrierHandover {
    fun travel(progress: Float): Float = progress.coerceIn(0f, 1f)
    fun overlayFraction(sourceAppearanceAlpha: Float): Float = sourceAppearanceAlpha.coerceIn(0f, 1f)
    fun handedOver(progress: Float): Boolean = progress > 0f && progress < 1f
}

/**
 * Carries one network glyph (Wi-Fi or cellular type) from the collapsed status row into the
 * two-line carrier label while the control center is dragged open.
 *
 * Expansion supplies geometry; the fake status row and carrier layout supply the native
 * appearance crossfade. The overlay replays the bound source view, retaining its native colors.
 * Nothing in here changes the source tint, vetoes a frame or requests layout.
 */
internal class CarrierTypeMotion {
    private var host: ViewGroup? = null
    private var target: View? = null
    private var source: View? = null
    private val location = IntArray(2)
    private var centerX = 0f
    private var centerY = 0f
    private var drawWidth = 0f
    private var drawHeight = 0f
    private var glyphAlpha = 255
    private var frameReady = false
    private var drawFailed = false

    private val layer = object : Drawable() {
        override fun draw(canvas: Canvas) {
            val view = source ?: return
            if (drawFailed || glyphAlpha <= 0 || drawWidth <= 0f || drawHeight <= 0f ||
                view.width <= 0 || view.height <= 0) return
            val outer = canvas.save()
            try {
                canvas.translate(centerX - drawWidth / 2f, centerY - drawHeight / 2f)
                PanelSourceGlyph.draw(canvas, view, drawWidth, drawHeight, glyphAlpha)
                if (!frameReady) {
                    frameReady = true
                    host?.postInvalidateOnAnimation()
                }
            } catch (error: Throwable) {
                drawFailed = true
                frameReady = false
                host?.postInvalidateOnAnimation()
                DebugLog.w("IconTuner", "carrier type transition drawing failed", error)
            } finally {
                canvas.restoreToCount(outer)
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * Positions the overlay between the native status row's glyph and the label row's endpoint.
     * Returns true only after one complete overlay draw, matching DuoPanelMotion's fail-open rule.
     */
    fun update(
        root: ViewGroup,
        source: View,
        target: View,
        progress: Float,
        sourceAppearanceAlpha: Float
    ): Boolean {
        if (progress <= 0f || progress >= 1f || !root.isAttachedToWindow ||
            root.width <= 0 || root.height <= 0 ||
            !source.isAttachedToWindow || source.width <= 0 || source.height <= 0 ||
            !target.isAttachedToWindow || target.width <= 0 || target.height <= 0
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
                DebugLog.w("IconTuner", "carrier type transition overlay unavailable", it)
                return false
            }
        }
        if (this.target !== target || this.source !== source) {
            this.source = source
            this.target = target
            frameReady = false
            drawFailed = false
        }

        val clamped = CarrierHandover.travel(progress)
        location(source)
        val sourceX = location[0] + source.width / 2f
        val sourceY = location[1] + source.height / 2f
        location(target)
        val targetX = location[0] + target.width / 2f
        val targetY = location[1] + target.height / 2f
        location(root)
        centerX = mix(sourceX, targetX, clamped) - location[0]
        centerY = mix(sourceY, targetY, clamped) - location[1]
        drawWidth = mix(source.width.toFloat(), target.width.toFloat(), clamped).coerceAtLeast(1f)
        drawHeight = mix(source.height.toFloat(), target.height.toFloat(), clamped).coerceAtLeast(1f)
        // The native fake row fades only when onAppearanceChanged hands over ownership.
        // Expansion moves the glyph; it must not select the control-center palette or fade clock.
        glyphAlpha = (CarrierHandover.overlayFraction(sourceAppearanceAlpha) * 255).toInt()
        layer.setBounds(0, 0, root.width, root.height)
        // The binder can change the source drawable/tint without moving it, so always invalidate.
        layer.invalidateSelf()
        return frameReady && !drawFailed
    }

    private fun location(view: View) {
        view.getLocationOnScreen(location)
    }

    private fun mix(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress.coerceIn(0f, 1f)

    fun clear() {
        host?.overlay?.remove(layer)
        host = null
        target = null
        source = null
        frameReady = false
        drawFailed = false
        centerX = 0f
        centerY = 0f
        drawWidth = 0f
        drawHeight = 0f
        glyphAlpha = 255
    }
}
