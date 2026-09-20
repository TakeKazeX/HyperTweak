package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import kotlin.math.abs

/** One subscription's vector row, independently owned from the network type and the other SIM. */
internal class SignalRowMotion {
    private var host: ViewGroup? = null
    private var target: View? = null
    private var savedAlpha = 1f
    private var appliedAlpha = 1f
    private var ready = false
    private var failed = false
    private var picture: Picture? = null
    private var destinationPicture: Picture? = null
    private var shapeFraction = 0f
    private val start = RectF()
    private val end = RectF()
    private val crop = RectF()
    private val destination = RectF()
    private val nextDestination = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tint = 0
    private var layerAlpha = 255
    private val matrix = Matrix()
    private val rootMatrix = Matrix()
    private val inverseRoot = Matrix()
    private val layer = object : Drawable() {
        override fun draw(canvas: Canvas) {
            val commands = picture ?: return
            if (failed || destination.isEmpty || crop.isEmpty) return
            val count = canvas.save()
            try {
                paint.alpha = this@SignalRowMotion.layerAlpha
                canvas.saveLayer(destination, paint)
                canvas.clipRect(destination)
                val sourceLayer = if (destinationPicture == null) canvas.save() else
                    canvas.saveLayerAlpha(destination, ((1f - shapeFraction) * 255).toInt())
                try {
                    canvas.translate(destination.left, destination.top)
                    canvas.scale(destination.width() / crop.width(), destination.height() / crop.height())
                    canvas.translate(-crop.left, -crop.top)
                    canvas.drawPicture(commands)
                } finally { canvas.restoreToCount(sourceLayer) }
                destinationPicture?.let { targetCommands ->
                    val targetLayer = canvas.saveLayerAlpha(destination, (shapeFraction * 255).toInt())
                    try { canvas.drawPicture(targetCommands, destination) }
                    finally { canvas.restoreToCount(targetLayer) }
                }
                if (!ready) {
                    ready = true
                    host?.postInvalidateOnAnimation()
                }
            } catch (error: Throwable) {
                failed = true
                ready = false
                restoreTarget()
                com.takekazex.hypertweak.util.DebugLog.w("DuoSignal", "SIM row handoff failed", error)
            } finally { canvas.restoreToCount(count) }
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    fun update(root: ViewGroup, source: View, sourceLocal: RectF, native: View,
               commands: Picture, pictureCrop: RectF, color: Int, progress: Float, targetCommands: Picture? = null): Boolean {
        if (!root.isAttachedToWindow || !native.isAttachedToWindow || !source.isAttachedToWindow ||
            native.width <= 0 || native.height <= 0 || sourceLocal.isEmpty) {
            clear(); return false
        }
        if (host !== root) { clear(); host = root; root.overlay.add(layer) }
        if (target !== native) {
            restoreTarget(); target = native; savedAlpha = native.alpha; appliedAlpha = native.alpha
            ready = false; failed = false
        }
        if (abs(native.alpha - appliedAlpha) > .001f) savedAlpha = native.alpha
        val changed = picture !== commands || crop != pictureCrop || tint != color || destinationPicture !== targetCommands
        picture = commands
        crop.set(pictureCrop)
        if (tint != color || paint.colorFilter == null) {
            tint = color; paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        start.set(sourceLocal)
        matrix.reset(); source.transformMatrixToGlobal(matrix); matrix.mapRect(start)
        end.set(0f, 0f, native.width.toFloat(), native.height.toFloat())
        if (native is ImageView) native.drawable?.let {
            end.set(it.bounds)
            native.imageMatrix.mapRect(end)
            end.offset(native.paddingLeft.toFloat(), native.paddingTop.toFloat())
        }
        matrix.reset(); native.transformMatrixToGlobal(matrix); matrix.mapRect(end)
        val p = progress.coerceIn(0f, 1f)
        nextDestination.set(DuoPanelGeometry.mix(start.left, end.left, p), DuoPanelGeometry.mix(start.top, end.top, p),
            DuoPanelGeometry.mix(start.right, end.right, p), DuoPanelGeometry.mix(start.bottom, end.bottom, p))
        rootMatrix.reset(); root.transformMatrixToGlobal(rootMatrix)
        if (!rootMatrix.invert(inverseRoot)) { clear(); return false }
        inverseRoot.mapRect(nextDestination)
        val nextAlpha = DuoPanelGeometry.overlayAlpha(p)
        val nextShape = if (targetCommands != null) p else 0f
        val needsDraw = changed || destination != nextDestination || layerAlpha != nextAlpha || shapeFraction != nextShape || !ready
        destination.set(nextDestination)
        layerAlpha = nextAlpha
        destinationPicture = targetCommands
        shapeFraction = nextShape
        appliedAlpha = savedAlpha * if (ready && !failed) DuoPanelGeometry.handoff(p) else 1f
        native.alpha = appliedAlpha
        layer.setBounds(0, 0, root.width, root.height)
        if (needsDraw) layer.invalidateSelf()
        return ready && !failed
    }

    private fun restoreTarget() {
        target?.let { if (abs(it.alpha - appliedAlpha) < .001f) it.alpha = savedAlpha }
        target = null
    }
    fun clear() {
        restoreTarget(); host?.overlay?.remove(layer); host = null
        picture = null; destinationPicture = null; shapeFraction = 0f; ready = false; failed = false
    }
}
