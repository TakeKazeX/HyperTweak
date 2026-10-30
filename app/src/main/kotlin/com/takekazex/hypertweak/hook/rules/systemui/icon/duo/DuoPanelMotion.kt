package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.presentation.icon.duo.NativeWifiDrawable

import com.takekazex.hypertweak.presentation.icon.duo.DuoContent
import com.takekazex.hypertweak.presentation.icon.duo.DuoDrawable
import com.takekazex.hypertweak.presentation.icon.duo.DuoSizes

import com.takekazex.hypertweak.settings.contract.IconSettingsKeys

import android.content.Context
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelGlyphFrame
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelGlyphTravel
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.min

// Published on settings acceptance; hook callbacks perform no storage reads.
private val settingIconMirrorAirplane by hookSetting(false) {
    com.takekazex.hypertweak.hook.Preferences.getBoolean(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE, false)
}

internal fun prepareDuoPanelMotionSettings() { settingIconMirrorAirplane }

/** An owned overlay, with no added children in host StatusIconDisplayable containers. */
internal class DuoPanelMotion(context: Context, private val airplaneOnly: Boolean = false) {
    private var host: ViewGroup? = null
    private var target: View? = null
    private var wifiArtwork: NativeWifiDrawable? = null
    private var savedAlpha = 1f
    private var appliedAlpha = 1f
    private val sourceFrame = PanelGlyphFrame()
    private val targetFrame = PanelGlyphFrame()
    private val travel = PanelGlyphTravel()
    private val from = RectF()
    private val to = RectF()
    private val matrix = Matrix()
    private val rootMatrix = Matrix()
    private val inverseRoot = Matrix()
    private var sourceKey: List<Any?>? = null
    private val glyph = DuoDrawable(context).apply {
        networkOnly = true
        mirrorAirplane = settingIconMirrorAirplane
    }
    private var x = 0f
    private var y = 0f
    private var side = 0f
    private var motionProgress = 0f
    private var layerAlpha = 255
    private var frameReady = false
    private var drawFailed = false
    private val layer = object : Drawable() {
        override fun draw(canvas: Canvas) {
            if (drawFailed || side <= 0f) return
            val count = canvas.save()
            try {
                if (layerAlpha > 0) travel.draw(canvas, sourceFrame, targetFrame, from, to, motionProgress, keepSource = airplaneOnly)
                if (!frameReady) {
                    frameReady = true
                    host?.postInvalidateOnAnimation()
                }
            } catch (error: Throwable) {
                drawFailed = true
                frameReady = false
                restoreTarget()
                host?.postInvalidateOnAnimation()
                com.takekazex.hypertweak.util.logging.DebugLog.w("DuoSignal", "network transition drawing failed", error)
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
        val p = progress.coerceIn(0f, 1f)
        val sourceView = source as? DuoIconView
        val sourceSide = (sourceView?.drawnIconSidePx() ?: min(source.width, source.height)).toFloat()
        val sourceTop = ((source.height - sourceSide.toInt()) / 2).toFloat()
        val sourceLeft = if (source.layoutDirection == View.LAYOUT_DIRECTION_RTL) source.paddingLeft.toFloat()
            else source.width - source.paddingRight - sourceSide
        val factor = if (airplaneOnly) .58f * sizes.airplane else if (content.wifiLevel != null)
            DuoDrawable.COMPACT_RING_SCALE * sizes.wifi * (if (usesNativeWifi) DuoDrawable.NATIVE_WIFI_SIZE / DuoDrawable.VIEWPORT else 1f)
            else sizes.type
        val boxSide = sourceSide * factor
        val cx = sourceLeft + sourceSide / 2f
        val cy = sourceTop + sourceSide * (if (airplaneOnly || content.wifiLevel == null) DuoDrawable.LABEL_CENTER_Y
            else DuoDrawable.COMPACT_RING_CENTER_Y) / DuoDrawable.VIEWPORT
        glyph.mirrorAirplane = settingIconMirrorAirplane
        glyph.content = rendered
        glyph.foreground = color
        glyph.small5GaEnabled = small5GaEnabled
        val key = listOf(rendered, color, small5GaEnabled, artwork, artwork?.revision, glyph.mirrorAirplane)
        if (sourceKey != key) {
            sourceKey = key
            if (!sourceFrame.capture(128, 128) { canvas ->
                if (artwork != null) {
                    artwork.setBounds(0, 0, 128, 128); artwork.alpha = 255; artwork.foreground = color; artwork.draw(canvas)
                } else { glyph.setBounds(0, 0, 128, 128); glyph.alpha = 255; glyph.draw(canvas) }
            }) { clear(); return false }
        }
        if (!targetFrame.captureView(native)) { clear(); return false }
        from.set(sourceFrame.ink)
        val sourceScale = boxSide / 128f
        from.set(cx - boxSide / 2f + from.left * sourceScale, cy - boxSide / 2f + from.top * sourceScale,
            cx - boxSide / 2f + from.right * sourceScale, cy - boxSide / 2f + from.bottom * sourceScale)
        matrix.reset(); source.transformMatrixToGlobal(matrix); matrix.mapRect(from)
        to.set(targetFrame.ink)
        matrix.reset(); native.transformMatrixToGlobal(matrix); matrix.mapRect(to)
        rootMatrix.reset(); root.transformMatrixToGlobal(rootMatrix)
        if (!rootMatrix.invert(inverseRoot)) { clear(); return false }
        inverseRoot.mapRect(from); inverseRoot.mapRect(to)
        x = from.left; y = from.top; side = from.width()
        motionProgress = p
        // Keep native artwork hidden until the matched endpoint. A separate native fade would
        // expose a full-sized destination behind the moving glyph.
        val handoff = if (p >= 1f) 1f else 0f
        appliedAlpha = savedAlpha * if (frameReady && !drawFailed) handoff else 1f
        if (native.alpha != appliedAlpha) native.alpha = appliedAlpha
        layerAlpha = if (p >= 1f) 0 else 255
        layer.setBounds(0, 0, root.width, root.height)
        if (contentChanged || previousX != x || previousY != y || previousSide != side || previousOpacity != layerAlpha || motionProgress > 0f)
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
        sourceKey = null
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
