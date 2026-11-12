package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.presentation.icon.AirplaneGlyph
import com.takekazex.hypertweak.presentation.icon.AirplaneLabelGeometry
import com.takekazex.hypertweak.settings.contract.AirplaneLabelMode
import com.takekazex.hypertweak.util.platform.ResourceLookup
import kotlin.math.roundToInt

/**
 * The airplane glyph drawn inside a host carrier label as a [ReplacementSpan].
 *
 * The tint comes from the text paint at draw time, so it follows dark/light and keyguard colors.
 * The size comes from the font metrics, so it follows the host typography. No view is inserted into
 * a host layout.
 */
internal class AirplaneGlyphSpan(
    private val glyph: Drawable,
    val order: AirplaneLabelGeometry.Order,
    val rtl: Boolean,
    val mirror: Boolean,
    private val gapPx: Float,
    /** The host text this span replaced; restoring the label sets it back verbatim. */
    val nativeText: String
) : ReplacementSpan() {
    private var tint: Int? = null

    /** Glyph opacity owned by a hand-over; the label text keeps its own alpha. */
    var glyphAlpha = 1f

    /** Where [draw] last put the glyph, in the text layout's coordinates; empty before the first draw. */
    val drawnBounds = RectF()
    var drawnColor = 0
        private set
    private var recorded: Picture? = null
    private var recordedKey: List<Any>? = null

    /**
     * The glyph alone at full opacity, in [view]'s own coordinates and size, as the hand-over measures
     * its destination. The host [TextView] places its layout at the compound/extended paddings plus
     * the vertical-gravity offset; a missing layout or an undrawn glyph returns null.
     */
    fun picture(view: TextView): Picture? {
        val layout = view.layout ?: return null
        if (drawnBounds.isEmpty || view.width <= 0 || view.height <= 0) return null
        val top = view.extendedPaddingTop
        val box = view.height - top - view.extendedPaddingBottom
        val voffset = if (layout.height >= box) 0 else when (view.gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.TOP -> 0
            Gravity.BOTTOM -> box - layout.height
            else -> (box - layout.height) / 2
        }
        val dx = (view.compoundPaddingLeft - view.scrollX).toFloat()
        val dy = (top + voffset - view.scrollY).toFloat()
        val key = listOf(view.width, view.height, dx, dy, RectF(drawnBounds), drawnColor, mirror)
        recorded?.takeIf { recordedKey == key }?.let { return it }
        val picture = Picture()
        val canvas = picture.beginRecording(view.width, view.height)
        try {
            // Picture recording dimensions alone do not clip replayed draw commands.
            canvas.clipRect(0, 0, view.width, view.height)
            canvas.translate(dx + drawnBounds.left, dy + drawnBounds.top)
            drawGlyph(canvas, drawnBounds.width(), drawnBounds.height(), drawnColor, 255)
        } finally {
            picture.endRecording()
        }
        recorded = picture
        recordedKey = key
        return picture
    }

    private fun box(paint: Paint): AirplaneLabelGeometry.Box {
        val metrics = paint.fontMetrics
        return AirplaneLabelGeometry.box(
            metrics.ascent, metrics.descent, glyph.intrinsicWidth, glyph.intrinsicHeight, gapPx, order, rtl
        )
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        // Keep the line metrics of the surrounding text, so the row height never changes.
        if (fm != null) paint.getFontMetricsInt(fm)
        return box(paint).advance
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val box = box(paint)
        if (box.glyphWidth <= 0f || box.glyphHeight <= 0f) return
        val left = x + box.glyphX
        val glyphTop = y + box.glyphTop
        drawnBounds.set(left, glyphTop, left + box.glyphWidth, glyphTop + box.glyphHeight)
        drawnColor = paint.color
        // A hidden glyph keeps its measured position for the hand-over.
        val alpha = (glyphAlpha.coerceIn(0f, 1f) * Color.alpha(paint.color)).roundToInt()
        if (alpha <= 0) return
        val saved = canvas.save()
        try {
            canvas.translate(left, glyphTop)
            drawGlyph(canvas, box.glyphWidth, box.glyphHeight, paint.color, alpha)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun drawGlyph(canvas: Canvas, width: Float, height: Float, color: Int, alpha: Int) {
        val opaque = color or (0xFF shl 24)
        if (tint != opaque) {
            glyph.setTint(opaque)
            tint = opaque
        }
        glyph.alpha = alpha
        glyph.setBounds(0, 0, width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1))
        if (mirror) canvas.scale(-1f, 1f, width / 2f, height / 2f)
        glyph.draw(canvas)
    }
}

/** Builds and recognizes airplane labels; callers own view state and restoration. */
internal object AirplaneLabelText {
    private const val HOST_PACKAGE = "com.android.systemui"
    /** The text `MiuiCarrierTextController` shows while airplane mode is on without VoWiFi. */
    private const val HOST_LABEL = "lock_screen_carrier_airplane_mode_on"
    private const val PLACEHOLDER = "￼"

    /** Resolved by name on every call, so a locale change uses the current translation. */
    fun hostLabel(resources: Resources): String? = runCatching {
        val id = ResourceLookup.identifier(resources, HOST_LABEL, "string", HOST_PACKAGE)
        if (id == 0) null else resources.getString(id)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** The host text behind [text], with a module span removed. */
    fun nativeText(text: CharSequence?): String? {
        if (text == null) return null
        if (text is Spanned) text.getSpans(0, text.length, AirplaneGlyphSpan::class.java).firstOrNull()
            ?.let { return it.nativeText }
        return text.toString()
    }

    fun isAirplaneLabel(view: TextView): Boolean {
        val label = hostLabel(view.resources) ?: return false
        return nativeText(view.text) == label
    }

    fun span(text: CharSequence?): AirplaneGlyphSpan? =
        (text as? Spanned)?.let { it.getSpans(0, it.length, AirplaneGlyphSpan::class.java).firstOrNull() }

    fun hasGlyph(text: CharSequence?): Boolean =
        text is Spanned && text.getSpans(0, text.length, AirplaneGlyphSpan::class.java).isNotEmpty()

    /**
     * Shows [native] in [view] as [mode] requires. Returns false when the glyph is unavailable, in
     * which case the plain host text stays.
     */
    fun apply(view: TextView, native: String, mode: AirplaneLabelMode, mirror: Boolean): Boolean {
        val order = AirplaneLabelGeometry.order(mode)
        if (order == null) {
            if (hasGlyph(view.text)) view.text = native
            return true
        }
        val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val current = (view.text as? Spanned)?.let { text ->
            text.getSpans(0, text.length, AirplaneGlyphSpan::class.java).firstOrNull()
        }
        if (current != null && current.nativeText == native && current.matches(order, rtl, mirror)) return true
        val glyph = AirplaneGlyph.loadGlyph(view.context) ?: run {
            if (hasGlyph(view.text)) view.text = native
            return false
        }
        val gap = AirplaneLabelGeometry.GAP_DP * view.resources.displayMetrics.density
        val span = AirplaneGlyphSpan(glyph, order, rtl, mirror, gap, native)
        val text = when (order) {
            AirplaneLabelGeometry.Order.GLYPH_ONLY -> SpannableString(native).apply {
                setSpan(span, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            AirplaneLabelGeometry.Order.GLYPH_FIRST -> SpannableString(PLACEHOLDER + native).apply {
                setSpan(span, 0, PLACEHOLDER.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            AirplaneLabelGeometry.Order.TEXT_FIRST -> SpannableString(native + PLACEHOLDER).apply {
                setSpan(span, native.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        view.text = text
        return true
    }

    private fun AirplaneGlyphSpan.matches(order: AirplaneLabelGeometry.Order, rtl: Boolean, mirror: Boolean): Boolean =
        this.order == order && this.rtl == rtl && this.mirror == mirror
}
