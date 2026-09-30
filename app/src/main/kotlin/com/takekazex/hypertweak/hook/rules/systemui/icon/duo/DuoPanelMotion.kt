package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.min

/** An owned overlay, with no added children in host StatusIconDisplayable containers. */
internal class DuoPanelMotion(context: Context, private val airplaneOnly: Boolean = false) {
    private var host: ViewGroup? = null
    private var target: View? = null
    private var wifiArtwork: NativeWifiDrawable? = null
    private var savedAlpha = 1f
    private var appliedAlpha = 1f
    private val location = IntArray(2)
    private val glyph = DuoDrawable(context).apply {
        networkOnly = true
        mirrorAirplane = com.takekazex.hypertweak.hook.Preferences.getBoolean(
            com.takekazex.hypertweak.hook.Preferences.KEY_ICON_MIRROR_AIRPLANE, false)
    }
    private var x = 0f
    private var y = 0f
    private var side = 0f
    private var layerAlpha = 255
    private var frameReady = false
    private var drawFailed = false
    private val layer = object : Drawable() {
        override fun draw(canvas: Canvas) {
            if (drawFailed || side <= 0f || this@DuoPanelMotion.layerAlpha <= 0) return
            val count = canvas.save()
            try {
                canvas.translate(x - side / 2f, y - side / 2f)
                val wifi = wifiArtwork
                if (wifi != null) {
                    wifi.setBounds(0, 0, side.toInt(), side.toInt())
                    wifi.alpha = this@DuoPanelMotion.layerAlpha
                    wifi.foreground = glyph.foreground
                    wifi.draw(canvas)
                } else {
                    glyph.setBounds(0, 0, side.toInt(), side.toInt())
                    // Drawable.opacity is a PixelFormat, not the overlay's 0..255 alpha.
                    glyph.alpha = this@DuoPanelMotion.layerAlpha
                    glyph.draw(canvas)
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
                com.takekazex.hypertweak.util.DebugLog.w("DuoSignal", "network transition drawing failed", error)
            } finally { canvas.restoreToCount(count) }
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun update(
        root: ViewGroup,
        source: View,
        native: View,
        content: DuoContent,
        color: Int,
        progress: Float,
        small5GaEnabled: Boolean = false,
        sizes: DuoSizes = DuoSizes(),
        nativeWifi: NativeWifiDrawable? = null
    ): Boolean {
        if (!progress.isFinite() || !root.isAttachedToWindow ||
            !source.isAttachedToWindow || source.width <= 0 || source.height <= 0 ||
            !native.isAttachedToWindow || native.width <= 0 || native.height <= 0) {
            clear(); return false
        }
        // Missing/temporarily unbound native artwork uses the same local fallback as Duo.
        // A hidden native view's layout or drawable bounds cannot cancel the gesture.
        val usesNativeWifi = !airplaneOnly && content.wifiLevel != null && nativeWifi?.isReady() == true
        val artwork = nativeWifi.takeIf { usesNativeWifi }
        val rendered = if (airplaneOnly) content.copy(wifiLevel = null) else content
        val previousX = x; val previousY = y; val previousSide = side; val previousOpacity = layerAlpha
        val contentChanged = glyph.content != rendered || glyph.foreground != color ||
            glyph.small5GaEnabled != small5GaEnabled || wifiArtwork !== artwork || host !== root || target !== native
        if (host !== root) { clear(); host = root; root.overlay.add(layer) }
        wifiArtwork = artwork
        if (target !== native) {
            restoreTarget(); target = native; savedAlpha = native.alpha; appliedAlpha = native.alpha
            frameReady = false
            drawFailed = false
        }
        if (abs(native.alpha - appliedAlpha) > .001f) savedAlpha = native.alpha
        val handoff = DuoPanelGeometry.handoff(progress)
        // Do not hide either endpoint until the overlay has actually drawn successfully.
        appliedAlpha = savedAlpha * if (frameReady && !drawFailed) handoff else 1f
        if (native.alpha != appliedAlpha) native.alpha = appliedAlpha
        source.getLocationOnScreen(location)
        val sourceSide = min(source.width, source.height).toFloat()
        val sourceX = location[0] + source.width - source.paddingRight - sourceSide / 2f
        val sourceY = location[1] + if ((airplaneOnly && content.wifiLevel != null) || (content.wifiLevel == null && content.networkLabel != null))
            sourceSide * DuoDrawable.LABEL_CENTER_Y / DuoDrawable.VIEWPORT else
            sourceSide * DuoDrawable.COMPACT_RING_CENTER_Y / DuoDrawable.VIEWPORT
        native.getLocationOnScreen(location)
        val targetX = location[0] + native.width / 2f
        val targetY = location[1] + native.height / 2f
        root.getLocationOnScreen(location)
        x = DuoPanelGeometry.mix(sourceX, targetX, progress) - location[0]
        y = DuoPanelGeometry.mix(sourceY, targetY, progress) - location[1]
        // Network artwork occupies roughly half the 32-unit viewport.
        side = DuoPanelGeometry.mix(min(source.width, source.height).toFloat() *
            (if (airplaneOnly) (if (content.wifiLevel != null) .58f else DuoDrawable.COMPACT_RING_SCALE) * sizes.airplane
                else if (content.wifiLevel != null) DuoDrawable.COMPACT_RING_SCALE * sizes.wifi *
                    (if (usesNativeWifi) DuoDrawable.NATIVE_WIFI_SIZE / DuoDrawable.VIEWPORT else 1f)
                else sizes.type),
            min(native.width, native.height) * (if (usesNativeWifi) 1f else 1.7f), progress)
        layerAlpha = DuoPanelGeometry.overlayAlpha(progress)
        glyph.content = rendered
        glyph.foreground = color
        glyph.small5GaEnabled = small5GaEnabled
        layer.setBounds(0, 0, root.width, root.height)
        if (contentChanged || previousX != x || previousY != y || previousSide != side || previousOpacity != layerAlpha)
            layer.invalidateSelf()
        return frameReady && !drawFailed
    }

    private fun restoreTarget() {
        target?.let { if (abs(it.alpha - appliedAlpha) < .001f) it.alpha = savedAlpha }
        target = null
    }
    fun clear() {
        restoreTarget()
        host?.overlay?.remove(layer)
        host = null
        wifiArtwork = null
        frameReady = false
        drawFailed = false
    }
}

internal data class DuoPanelPoint(val x: Float, val y: Float)

/** An additive correction, restoring only the value still owned by this writer. */
internal class DuoOwnedTranslation {
    private var baseline: Float? = null
    private var applied = 0f

    fun apply(current: Float, delta: Float): Float {
        if (baseline == null || abs(current - applied) > .001f) baseline = current
        applied = current + delta
        return applied
    }

    fun restore(current: Float): Float {
        val result = baseline?.takeIf { abs(current - applied) <= .001f } ?: current
        baseline = null
        return result
    }
}

internal object DuoPanelGeometry {
    fun position(home: DuoPanelPoint, expanded: DuoPanelPoint, progress: Float,
        stretch: DuoPanelPoint = DuoPanelPoint(0f, 0f)): DuoPanelPoint = DuoPanelPoint(
        mix(home.x, expanded.x, progress) + stretch.x,
        mix(home.y, expanded.y, progress) + stretch.y
    )

    /** The same endpoint hand-off fraction used by the Duo Wi-Fi overlay. */
    fun handoff(progress: Float): Float = ((progress - .75f) / .25f).coerceIn(0f, 1f)

    fun overlayFraction(progress: Float): Float = 1f - handoff(progress)

    fun overlayAlpha(progress: Float): Int {
        return (overlayFraction(progress) * 255f).toInt().coerceIn(0, 255)
    }
    fun mix(start: Float, end: Float, progress: Float) = start + (end - start) * progress.coerceIn(0f, 1f)
}
