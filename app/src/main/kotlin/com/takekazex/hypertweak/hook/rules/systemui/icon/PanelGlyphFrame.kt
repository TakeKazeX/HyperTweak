package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Records only one component; its software alpha mask measures ink, never the whole screen. */
internal class PanelGlyphFrame {
    val ink = RectF()
    val picture = Picture()
    var revision = 0
        private set
    private var bitmap: Bitmap? = null
    private var sampleScale = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fitted = RectF()
    private var viewKey: List<Any?>? = null
    private var peak = 0
    private var rgb = Color.WHITE
    private var solid = false
    private var reportedFailure = false

    fun invalidateView() { viewKey = null }

    fun captureView(view: View, crop: RectF? = null): Boolean {
        val image = view as? ImageView
        val text = view as? TextView
        val key = listOf(view, view.width, view.height, view.paddingLeft, view.paddingTop,
            view.paddingRight, view.paddingBottom, image?.drawable, (image?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap, image?.drawable?.level, image?.drawable?.alpha, image?.colorFilter,
            image?.drawable?.colorFilter, image?.imageTintList, image?.imageAlpha,
            view.drawableState.contentHashCode(), text?.text?.toString(), text?.textSize,
            text?.typeface, text?.letterSpacing, text?.currentTextColor, text?.includeFontPadding,
            text?.gravity, text?.baseline, image?.scaleType, crop?.let(::RectF))
        // Drawable tint/paint can mutate in place without changing resource identity.
        if (key == viewKey && !view.isDirty) return !ink.isEmpty
        viewKey = key
        return capture(view.width, view.height, crop) { view.draw(it) }
    }

    fun capture(width: Int, height: Int, crop: RectF? = null, draw: (Canvas) -> Unit): Boolean = runCatching {
        captureInternal(width, height, crop, draw).also { if (it) reportedFailure = false }
    }.getOrElse { error ->
        ink.setEmpty()
        if (!reportedFailure) {
            reportedFailure = true
            com.takekazex.hypertweak.util.logging.DebugLog.w("DuoSignal", "component ink recording unavailable", error)
        }
        false
    }

    private fun captureInternal(width: Int, height: Int, crop: RectF?, draw: (Canvas) -> Unit): Boolean {
        ink.setEmpty()
        if (width <= 0 || height <= 0) return false
        val recording = picture.beginRecording(width, height)
        try {
            if (crop != null) recording.clipRect(crop)
            draw(recording)
        } finally { picture.endRecording() }
        sampleScale = min(1f, 256f / maxOf(width, height))
        val w = ceil(width * sampleScale).toInt().coerceAtLeast(1)
        val h = ceil(height * sampleScale).toInt().coerceAtLeast(1)
        val pixels = bitmap?.takeIf { it.width == w && it.height == h }
            ?: createBitmap(w, h).also { bitmap = it }
        pixels.eraseColor(Color.TRANSPARENT)
        Canvas(pixels).apply { scale(sampleScale, sampleScale); drawPicture(picture) }
        var left = w; var top = h; var right = -1; var bottom = -1
        peak = 0
        for (y in 0 until h) for (x in 0 until w) {
            val color = pixels[x, y]
            val alpha = Color.alpha(color)
            if (alpha <= 8) continue
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            if (alpha > peak) { peak = alpha; rgb = color or (0xff shl 24) }
        }
        if (right < left) return false
        ink.set(left / sampleScale, top / sampleScale, (right + 1) / sampleScale, (bottom + 1) / sampleScale)
        solid = true
        for (y in top..bottom) for (x in left..right) {
            val c = pixels[x, y]
            if (Color.alpha(c) > 24 && (abs(Color.red(c) - Color.red(rgb)) > 5 ||
                abs(Color.green(c) - Color.green(rgb)) > 5 || abs(Color.blue(c) - Color.blue(rgb)) > 5)) solid = false
        }
        revision++
        return true
    }

    fun colorAt(x: Float, y: Float): Int = bitmap?.let {
        it[(x * sampleScale).toInt().coerceIn(0, it.width - 1),
            (y * sampleScale).toInt().coerceIn(0, it.height - 1)]
    } ?: Color.TRANSPARENT

    fun sameShape(other: PanelGlyphFrame): Boolean {
        if (!solid || !other.solid || abs(peak - other.peak) > 3 || ink.isEmpty || other.ink.isEmpty) return false
        var different = 0
        for (y in 0 until 24) for (x in 0 until 24) {
            val a = Color.alpha(colorAt(ink.left + (x + .5f) / 24 * ink.width(), ink.top + (y + .5f) / 24 * ink.height()))
            val b = Color.alpha(other.colorAt(other.ink.left + (x + .5f) / 24 * other.ink.width(), other.ink.top + (y + .5f) / 24 * other.ink.height()))
            if (abs(a - b) > 48) different++
        }
        return different <= 48
    }

    fun draw(canvas: Canvas, destination: RectF, alpha: Int = 255, tint: Int? = null) {
        if (ink.isEmpty || destination.isEmpty || alpha <= 0) return
        val scale = min(destination.width() / ink.width(), destination.height() / ink.height())
        fitted.set(destination.centerX() - ink.width() * scale / 2f, destination.centerY() - ink.height() * scale / 2f,
            destination.centerX() + ink.width() * scale / 2f, destination.centerY() + ink.height() * scale / 2f)
        paint.alpha = alpha
        paint.colorFilter = tint?.let { PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
        val save = canvas.saveLayer(null, paint)
        try {
            canvas.translate(fitted.left, fitted.top)
            canvas.scale(scale, scale)
            canvas.translate(-ink.left, -ink.top)
            canvas.drawPicture(picture)
        } finally { canvas.restoreToCount(save) }
    }

    fun blendedColor(other: PanelGlyphFrame, progress: Float) = ColorUtils.blendARGB(rgb, other.rgb, progress)
}

internal class PanelGlyphTravel {
    private val current = RectF()
    private var sourceRevision = -1
    private var targetRevision = -1
    private var identical = false
    fun draw(canvas: Canvas, source: PanelGlyphFrame, target: PanelGlyphFrame, from: RectF, to: RectF, progress: Float,
             keepSource: Boolean = false) {
        val p = progress.coerceIn(0f, 1f)
        current.set(from.left + (to.left - from.left) * p, from.top + (to.top - from.top) * p,
            from.right + (to.right - from.right) * p, from.bottom + (to.bottom - from.bottom) * p)
        if (sourceRevision != source.revision || targetRevision != target.revision) {
            sourceRevision = source.revision; targetRevision = target.revision
            identical = source.sameShape(target)
        }
        if (identical || keepSource) source.draw(canvas, current, tint = source.blendedColor(target, p)) else {
            val t = ((p - .8f) / .2f).coerceIn(0f, 1f)
            val style = t * t * (3f - 2f * t)
            source.draw(canvas, current, ((1f - style) * 255).roundToInt())
            target.draw(canvas, current, (style * 255).roundToInt())
        }
    }
}
