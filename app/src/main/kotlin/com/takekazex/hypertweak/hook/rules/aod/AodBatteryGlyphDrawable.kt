package com.takekazex.hypertweak.hook.rules.aod

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.HostBatteryDrawable
import com.takekazex.hypertweak.util.DebugLog

/** Draws the stock AOD battery inside Duo, including before the source View's first layout. */
internal class AodBatteryGlyphDrawable(private val source: View) : Drawable() {
    private val host = HostBatteryDrawable(source)
    private var opacity = 255
    private var reportedDrawFailure = false

    override fun getIntrinsicWidth(): Int = source.width.takeIf { it > 0 }
        ?: (source as? ImageView)?.drawable?.intrinsicWidth?.takeIf { it > 0 }
        ?: 20

    override fun getIntrinsicHeight(): Int = source.height.takeIf { it > 0 }
        ?: (source as? ImageView)?.drawable?.intrinsicHeight?.takeIf { it > 0 }
        ?: 12

    override fun draw(canvas: Canvas) {
        if (source.isAttachedToWindow && source.width > 0 && source.height > 0) {
            host.bounds = bounds
            host.alpha = opacity
            runCatching { host.draw(canvas) }
                .onSuccess { return }
                .onFailure { error ->
                    if (!reportedDrawFailure) {
                        reportedDrawFailure = true
                        DebugLog.w("AodStatusIcon", "native AOD battery draw failed", error)
                    }
                }
        }
        val image = (source as? ImageView)?.drawable ?: return
        val oldBounds = Rect(image.bounds)
        val oldAlpha = image.alpha
        try {
            image.bounds = bounds
            image.alpha = opacity
            image.draw(canvas)
        } finally {
            image.bounds = oldBounds
            image.alpha = oldAlpha
        }
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
