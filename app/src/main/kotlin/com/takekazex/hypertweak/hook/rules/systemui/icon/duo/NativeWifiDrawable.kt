package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.ImageView

/** Own native tint-mode artwork; the hidden ImageView supplies only its current resource identity. */
internal class NativeWifiDrawable(
    val source: ImageView,
    private val tintResource: (Int) -> Int
) : Drawable() {
    private var opacity = 255
    var foreground = Color.WHITE
    private var sourceResource = 0
    private var sourceDrawable: Drawable? = null
    private var artwork: Drawable? = null
    var revision = 0
        private set
    private var appliedColor: Int? = null
    private var appliedAlpha = -1

    fun isReady(): Boolean {
        val resource = (source.tag as? Int)?.takeIf { it > 0 } ?: return artwork != null
        val current = source.drawable
        if (resource != sourceResource || current !== sourceDrawable) {
            sourceResource = resource
            sourceDrawable = current
            // Native dark-mode vectors contain a 75% alpha fill. Use SystemUI's tint-mode
            // variant, which preserves signal-strength reserves without that extra dimming.
            artwork = runCatching {
                source.context.getDrawable(tintResource(resource))?.mutate()?.apply {
                    clearColorFilter()
                    alpha = 255
                }
            }.onFailure {
                com.takekazex.hypertweak.util.DebugLog.w("DuoSignal", "native Wi-Fi artwork unavailable", it)
            }.getOrNull()
            appliedColor = null
            appliedAlpha = -1
            revision++
        }
        return artwork != null
    }

    override fun draw(canvas: Canvas) {
        if (!isReady() || bounds.isEmpty) return
        val native = artwork ?: return
        if (appliedColor != foreground) {
            native.setTint(foreground)
            appliedColor = foreground
        }
        if (appliedAlpha != opacity) {
            native.alpha = opacity
            appliedAlpha = opacity
        }
        native.bounds = bounds
        native.draw(canvas)
    }

    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255) }
    // Host drawable, binder and callback are never modified.
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
