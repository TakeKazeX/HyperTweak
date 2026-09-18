package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoPanelGeometry
import com.takekazex.hypertweak.util.DebugLog

/**
 * Hand-over curves for the two-line carrier block's trailing network glyph.
 *
 * The glyph is handed over exactly like Duo's middle Wi-Fi/cellular layer and the left-placed
 * status icons: one module-owned overlay follows the host's own expansion fraction from the
 * collapsed status row to the expanded label row, and the label's real glyph is revealed only in
 * the last quarter. Pure, so the numbers are unit-tested; the view work lives in
 * [ControlCenterCarrierBlockHooker].
 */
internal object CarrierHandover {
    /** The overlay's centre travels across the complete host expansion fraction. */
    fun travel(progress: Float): Float = progress.coerceIn(0f, 1f)

    /** Overlay opacity fraction, shared with Duo's middle-network hand-over. */
    fun overlayFraction(progress: Float): Float = DuoPanelGeometry.overlayFraction(progress)

    /** Label glyph opacity fraction, shared with Duo's last-quarter hand-over. */
    fun destinationAlpha(progress: Float): Float = DuoPanelGeometry.handoff(progress)

    /** True while the shade owns part of the hand-over. At 0 the label is the only owner. */
    fun handedOver(progress: Float): Boolean = progress > 0f && progress < 1f
}

/**
 * Carries one network glyph (Wi-Fi or cellular type) from the collapsed status row into the
 * two-line carrier label while the control center is dragged open.
 *
 * Deliberately modelled on `DuoPanelMotion`: the host expansion fraction is the only clock, the
 * intermediate image lives in the root `ViewGroupOverlay`, and the endpoint is revealed only after
 * the overlay has actually drawn once. Nothing in here vetoes a frame or requests layout.
 */
internal class CarrierTypeMotion {
    private var host: ViewGroup? = null
    private var target: View? = null
    private var bitmap: Bitmap? = null
    private var tint = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = Rect()
    private val location = IntArray(2)
    private var centerX = 0f
    private var centerY = 0f
    private var drawHeight = 0f
    private var glyphAlpha = 255
    private var frameReady = false
    private var drawFailed = false

    private val layer = object : Drawable() {
        override fun draw(canvas: Canvas) {
            val source = bitmap ?: return
            if (source.isRecycled || drawFailed || glyphAlpha <= 0 || drawHeight <= 0f) return
            val outer = canvas.save()
            try {
                val aspect = source.width.toFloat() / source.height.coerceAtLeast(1).toFloat()
                val width = (drawHeight * aspect).coerceAtLeast(1f)
                dst.set(
                    (centerX - width / 2f).toInt(),
                    (centerY - drawHeight / 2f).toInt(),
                    (centerX + width / 2f).toInt(),
                    (centerY + drawHeight / 2f).toInt()
                )
                src.set(0, 0, source.width, source.height)
                paint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                paint.alpha = glyphAlpha
                canvas.drawBitmap(source, src, dst, paint)
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
        bitmap: Bitmap,
        tint: Int,
        progress: Float
    ): Boolean {
        if (progress <= 0f || progress >= 1f || !root.isAttachedToWindow ||
            root.width <= 0 || root.height <= 0 ||
            !source.isAttachedToWindow || source.width <= 0 || source.height <= 0 ||
            !target.isAttachedToWindow || target.width <= 0 || target.height <= 0 ||
            bitmap.isRecycled
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
        if (this.target !== target) {
            this.target = target
            frameReady = false
            drawFailed = false
        }
        this.bitmap = bitmap
        this.tint = tint

        val clamped = progress.coerceIn(0f, 1f)
        location(source)
        val sourceX = location[0] + source.width / 2f
        val sourceY = location[1] + source.height / 2f
        location(target)
        val targetX = location[0] + target.width / 2f
        val targetY = location[1] + target.height / 2f
        location(root)
        centerX = mix(sourceX, targetX, clamped) - location[0]
        centerY = mix(sourceY, targetY, clamped) - location[1]
        drawHeight = mix(source.height.toFloat(), target.height.toFloat(), clamped).coerceAtLeast(1f)
        glyphAlpha = (DuoPanelGeometry.overlayAlpha(clamped)).coerceIn(0, 255)
        layer.setBounds(0, 0, root.width, root.height)
        // The glyph bitmap can be replaced without any geometry change, so always invalidate.
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
        bitmap = null
        frameReady = false
        drawFailed = false
        centerX = 0f
        centerY = 0f
        drawHeight = 0f
        glyphAlpha = 255
    }
}
