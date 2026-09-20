package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View

/** The host draws progress/tint outside ImageView.drawable. Keep that complete drawing isolated:
 * its CLEAR blend mode must never erase the surrounding Duo ring. No host geometry is mutated. */
internal class HostBatteryDrawable(val source: View, private val percentagePaints: List<Paint> = emptyList()) : Drawable() {
    private var layerAlpha = 255
    private val savedTextSizes = FloatArray(percentagePaints.size)
    override fun getIntrinsicWidth(): Int = source.width
    override fun getIntrinsicHeight(): Int = source.height
    override fun draw(canvas: Canvas) {
        check(source.width > 0 && source.height > 0 && source.isAttachedToWindow)
        val count = canvas.save()
        try {
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width().toFloat() / source.width, bounds.height().toFloat() / source.height)
            canvas.saveLayerAlpha(0f, 0f, source.width.toFloat(), source.height.toFloat(), layerAlpha)
            // Hollow battery punches its text out with CLEAR. Zero text size suppresses both
            // the punch-out and foreground, unlike alpha=0 which still clears the background.
            percentagePaints.forEachIndexed { index, paint ->
                savedTextSizes[index] = paint.textSize
                paint.textSize = 0f
            }
            try { source.draw(canvas) } finally {
                percentagePaints.forEachIndexed { index, paint -> paint.textSize = savedTextSizes[index] }
            }
        } finally { canvas.restoreToCount(count) }
    }
    override fun setAlpha(alpha: Int) { layerAlpha = alpha.coerceIn(0, 255) }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
