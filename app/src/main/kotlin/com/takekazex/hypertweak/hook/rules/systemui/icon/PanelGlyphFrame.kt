package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
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
    private var samples = IntArray(0)
    private var nextSamples = IntArray(0)
    private var recordedWidth = 0
    private var recordedHeight = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fitted = RectF()
    private var viewKey = PanelGlyphViewState()
    private var nextViewKey = PanelGlyphViewState()
    private var viewKeyValid = false
    private val sampleCanvas = Canvas()
    private var tintColor = 0
    private var tintFilter: PorterDuffColorFilter? = null
    private var lastView: View? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastCrop: RectF? = null
    private var peak = 0
    private var rgb = Color.WHITE
    private var solid = false
    private var reportedFailure = false

    fun invalidateView() { viewKeyValid = false; lastView = null; viewKey.clear(); nextViewKey.clear() }

    fun captureView(view: View, crop: RectF? = null): Boolean {
        // The panel drag asks for the same endpoints every frame. A plain bitmap ImageView can
        // only change through its drawable identity/geometry, so skip the key allocation when it
        // is clean; other views keep the full key comparison.
        val image = view as? ImageView
        val staticFastPath = image?.javaClass == ImageView::class.java &&
            image.drawable?.javaClass == BitmapDrawable::class.java &&
            image.background == null && image.foreground == null
        if (staticFastPath && view === lastView && view.width == lastWidth &&
            view.height == lastHeight && viewKeyValid && !view.isDirty && crop == lastCrop) {
            return !ink.isEmpty
        }
        nextViewKey.read(view, crop, viewKey)
        // Drawable tint/paint can mutate in place without changing resource identity.
        // Alpha-masked endpoints can stay dirty indefinitely because their parent skips drawing
        // them. A plain bitmap's generation plus ImageView appearance fully identifies its ink.
        val staticBitmap = staticFastPath
        if (viewKeyValid && nextViewKey.same(viewKey) && (staticBitmap || !view.isDirty)) return !ink.isEmpty
        val previousKey = viewKey
        viewKey = nextViewKey; nextViewKey = previousKey; viewKeyValid = true
        lastView = view
        lastWidth = view.width
        lastHeight = view.height
        if (crop == null) lastCrop = null else (lastCrop ?: RectF().also { lastCrop = it }).set(crop)
        return captureSafely(view.width, view.height, crop, null, view)
    }

    fun capture(width: Int, height: Int, crop: RectF? = null, draw: (Canvas) -> Unit): Boolean =
        captureSafely(width, height, crop, draw, null)

    private fun captureSafely(width: Int, height: Int, crop: RectF?, draw: ((Canvas) -> Unit)?, view: View?): Boolean = runCatching {
        captureInternal(width, height, crop, draw, view).also { if (it) reportedFailure = false }
    }.getOrElse { error ->
        ink.setEmpty()
        if (!reportedFailure) {
            reportedFailure = true
            com.takekazex.hypertweak.util.logging.DebugLog.w("DuoSignal", "component ink recording unavailable", error)
        }
        false
    }

    private fun captureInternal(width: Int, height: Int, crop: RectF?, draw: ((Canvas) -> Unit)?, view: View?): Boolean {
        if (width <= 0 || height <= 0) { ink.setEmpty(); return false }
        val recording = picture.beginRecording(width, height)
        try {
            if (crop != null) recording.clipRect(crop)
            if (view != null) view.draw(recording) else checkNotNull(draw)(recording)
        } finally { picture.endRecording() }
        sampleScale = min(1f, 256f / maxOf(width, height))
        val w = ceil(width * sampleScale).toInt().coerceAtLeast(1)
        val h = ceil(height * sampleScale).toInt().coerceAtLeast(1)
        val pixels = bitmap?.takeIf { it.width == w && it.height == h }
            ?: createBitmap(w, h).also { bitmap = it; sampleCanvas.setBitmap(it) }
        pixels.eraseColor(Color.TRANSPARENT)
        val save = sampleCanvas.save()
        try { sampleCanvas.scale(sampleScale, sampleScale); sampleCanvas.drawPicture(picture) }
        finally { sampleCanvas.restoreToCount(save) }
        if (nextSamples.size != w * h) nextSamples = IntArray(w * h)
        pixels.getPixels(nextSamples, 0, w, 0, 0, w, h)
        val changed = recordedWidth != width || recordedHeight != height || !samples.contentEquals(nextSamples)
        val previous = samples
        samples = nextSamples
        nextSamples = previous
        recordedWidth = width
        recordedHeight = height
        if (!changed && !reportedFailure) return !ink.isEmpty
        ink.setEmpty()
        var left = w; var top = h; var right = -1; var bottom = -1
        peak = 0
        for (y in 0 until h) for (x in 0 until w) {
            val color = samples[y * w + x]
            val alpha = Color.alpha(color)
            if (alpha <= 8) continue
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            if (alpha > peak) { peak = alpha; rgb = color or (0xff shl 24) }
        }
        if (right < left) return false
        ink.set(left / sampleScale, top / sampleScale, (right + 1) / sampleScale, (bottom + 1) / sampleScale)
        solid = true
        for (y in top..bottom) for (x in left..right) {
            val c = samples[y * w + x]
            if (Color.alpha(c) > 24 && (abs(Color.red(c) - Color.red(rgb)) > 5 ||
                abs(Color.green(c) - Color.green(rgb)) > 5 || abs(Color.blue(c) - Color.blue(rgb)) > 5)) solid = false
        }
        // Dirty masked views may be recorded again without any visible change. Such a capture
        // must not schedule another overlay frame (which would keep the source dirty again).
        if (changed) revision++
        return true
    }

    fun colorAt(x: Float, y: Float): Int = bitmap?.let {
        samples[(y * sampleScale).toInt().coerceIn(0, it.height - 1) * it.width +
            (x * sampleScale).toInt().coerceIn(0, it.width - 1)]
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
        paint.colorFilter = if (tint == null) null else {
            if (tintFilter == null || tintColor != tint) {
                tintColor = tint
                tintFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            }
            tintFilter
        }
        val save = canvas.saveLayer(null, paint)
        try {
            canvas.translate(fitted.left, fitted.top)
            canvas.scale(scale, scale)
            canvas.translate(-ink.left, -ink.top)
            canvas.drawPicture(picture)
        } finally { canvas.restoreToCount(save) }
    }

    fun blendFrom(color: Int, progress: Float): Int =
        ColorUtils.blendARGB(color, (rgb and 0x00ffffff) or (peak shl 24), progress)

    fun blendedColor(other: PanelGlyphFrame, progress: Float) = ColorUtils.blendARGB(rgb, other.rgb, progress)
}

internal class PanelGlyphTravel {
    private val current = RectF()
    private var sourceRevision = -1
    private var targetRevision = -1
    private var identical = false
    fun draw(canvas: Canvas, source: PanelGlyphFrame, target: PanelGlyphFrame, from: RectF, to: RectF, progress: Float,
             keepSource: Boolean = false, styleStart: Float = .8f, sameArtwork: Boolean = false) {
        val p = progress.coerceIn(0f, 1f)
        current.set(from.left + (to.left - from.left) * p, from.top + (to.top - from.top) * p,
            from.right + (to.right - from.right) * p, from.bottom + (to.bottom - from.bottom) * p)
        if (sameArtwork) {
            source.draw(canvas, current)
            return
        }
        if (sourceRevision != source.revision || targetRevision != target.revision) {
            sourceRevision = source.revision; targetRevision = target.revision
            identical = source.sameShape(target)
        }
        if (identical || keepSource) source.draw(canvas, current, tint = source.blendedColor(target, p)) else {
            val t = ((p - styleStart) / (1f - styleStart)).coerceIn(0f, 1f)
            val style = t * t * (3f - 2f * t)
            source.draw(canvas, current, ((1f - style) * 255).roundToInt())
            target.draw(canvas, current, (style * 255).roundToInt())
        }
    }
}
