package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import androidx.core.graphics.createBitmap
import kotlin.math.abs
import kotlin.math.roundToInt

private const val ALPHA_EPSILON = 0.001f

/**
 * Alpha steps from [current] towards [target], exclusive of [current] and inclusive of [target].
 *
 * Pure so the curve is unit-testable: the steps are evenly spaced and the last one is exactly the
 * target, which is what keeps a suppressed glyph fully invisible instead of almost.
 */
internal fun fadeAlphas(current: Float, target: Float, frames: Int): List<Float> {
    val from = current.coerceIn(0f, 1f)
    val to = target.coerceIn(0f, 1f)
    val count = frames.coerceAtLeast(1)
    if (abs(from - to) < ALPHA_EPSILON) return listOf(to)
    return (1..count).map { step -> from + (to - from) * step / count }
}

/** A copy of [source] at its own size with the alpha scaled, so a faded mask keeps its box. */
internal fun scaledAlphaBitmap(source: Bitmap, alpha: Float): Bitmap {
    val frame = createBitmap(source.width, source.height)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt().coerceIn(0, 255)
    }
    Canvas(frame).drawBitmap(source, 0f, 0f, paint)
    return frame
}

/**
 * Short alpha fade for one cellular-type glyph, driven on the host main looper.
 *
 * Both type renderers need the same thing: when Wi-Fi takes the data connection the glyph must
 * disappear *without* giving up its width, because the status-icon row is laid out from the trailing
 * edge and the carrier row's name budget is measured from the trailing glyphs. Collapsing the glyph
 * pulls the neighbouring icons sideways, so the glyph is republished at its own size with a lower
 * bitmap alpha instead, and the boxes never move.
 *
 * The fade owns only the alpha and calls [animate]'s closure; the caller decides what to publish.
 */
internal class BitmapAlphaFade(
    private val handler: Handler,
    private val frames: Int = FADE_FRAMES,
    private val frameMs: Long = FADE_FRAME_MS
) {
    private var current = 1f
    private var pending: Runnable? = null

    /** Last applied alpha; 0 means the glyph is present but invisible. */
    val alpha: Float get() = current

    /**
     * Fades towards [visible]'s alpha and publishes every frame (including the alpha it starts
     * from), so a new bitmap is drawn even when the alpha does not change. [onComplete] runs once
     * the ramp has reached the target (or immediately when it was already there) — that is where a
     * caller releases the glyph's box, after the fade has made it invisible.
     */
    fun animate(visible: Boolean, apply: (Float) -> Unit, onComplete: (() -> Unit)? = null) {
        cancel()
        val target = if (visible) 1f else 0f
        apply(current)
        val steps = fadeAlphas(current, target, frames)
        if (steps.size == 1 && abs(steps.first() - current) < ALPHA_EPSILON) {
            onComplete?.invoke()
            return
        }
        runFrame(steps, 0, apply, onComplete)
    }

    /**
     * Publishes at [visible]'s alpha without a ramp.
     *
     * A fresh bind must not animate: the first paint after the slot (or the row) is created happens
     * once per SystemUI generation, and fading *into* the state the user is already looking at would
     * flash the glyph for the length of the ramp.
     */
    fun snap(visible: Boolean, apply: (Float) -> Unit) {
        cancel()
        current = if (visible) 1f else 0f
        apply(current)
    }

    fun cancel() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    private fun runFrame(
        steps: List<Float>,
        index: Int,
        apply: (Float) -> Unit,
        onComplete: (() -> Unit)?
    ) {
        if (index >= steps.size) {
            onComplete?.invoke()
            return
        }
        val runnable = Runnable {
            pending = null
            val next = steps[index]
            current = next
            apply(next)
            runFrame(steps, index + 1, apply, onComplete)
        }
        pending = runnable
        handler.postDelayed(runnable, frameMs)
    }

    internal companion object {
        /** ~160 ms: short enough not to lag a Wi-Fi hand-off, long enough to read as a fade. */
        const val FADE_FRAMES = 8
        const val FADE_FRAME_MS = 20L
    }
}
