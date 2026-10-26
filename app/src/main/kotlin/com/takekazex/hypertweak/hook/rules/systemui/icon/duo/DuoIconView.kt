package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.presentation.icon.duo.DuoContent
import com.takekazex.hypertweak.presentation.icon.duo.DuoDrawable
import com.takekazex.hypertweak.presentation.icon.duo.DuoLayout
import com.takekazex.hypertweak.presentation.icon.duo.DuoRenderLayer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.core.view.isVisible
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlin.math.ceil
import kotlin.math.roundToInt

// Published on settings acceptance; hook callbacks perform no storage reads.
private val settingIconMirrorAirplane by hookSetting(false) { Preferences.getBoolean(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE, false) }

internal fun prepareDuoIconViewSettings() { settingIconMirrorAirplane }

/** Draws Duo content and percentage text without owning host hooks. */
internal class DuoIconView(context: Context) : View(context) {
    val icon = DuoDrawable(context).also {
        it.callback = this
        it.mirrorAirplane = settingIconMirrorAirplane
    }
    var drawFailed: ((Throwable) -> Unit)? = null
    private var reportedDrawFailure = false
    /** User glyph height in dp; both measurement and explicit layout derive their box from it. */
    var iconSizeDp = DuoLayout.DEFAULT_ICON_SIZE_DP.toFloat()
    private val transition = DuoNetworkTransition()
    private val contentLayers = LinkedHashMap<DuoRepresentation, DuoDrawable>()
    private val ring = DuoDrawable(context, DuoRenderLayer.POWER_TRACK)
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val percentMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var percentText = ""
    private var percentMark = ""
    private var percentLetterSpacing = 0f
    private var percentMarkLetterSpacing = 0f
    private var percentMarkVerticalOffset = 0f
    private var percentGapPx = 0f
    private var percentBelow = false
    private var animator: ValueAnimator? = null

    /** Mirrors the native percent TextViews beside the glyph or below an expanded battery ring. */
    fun setPercent(
        enabled: Boolean,
        below: Boolean,
        container: View?,
        value: TextView?,
        mark: TextView?,
        fallbackText: String? = null
    ) {
        val rawText = value?.text?.toString().orEmpty()
        val nextText = rawText.ifBlank { fallbackText.orEmpty() }
        val sourceReady = nextText.isNotEmpty()
        val activeValue = value?.takeIf {
            enabled && sourceReady && (below ||
                (container?.isVisible == true && it.isVisible))
        }
        val drawValue = enabled && sourceReady && (below || activeValue != null)
        val activeMark = mark?.takeIf {
            drawValue && (below || it.isVisible)
        }
        val nextMark = if (drawValue) activeMark?.text?.toString().orEmpty().ifBlank {
            if (below) "%" else ""
        } else ""
        val nextLetterSpacing = activeValue?.letterSpacing ?: 0f
        val nextMarkLetterSpacing = activeMark?.letterSpacing ?: 0f
        val density = resources.displayMetrics.density
        val oldSlot = leadingPercentSlotWidthPx()
        val changed = percentBelow != (below && drawValue) ||
            percentText != (if (drawValue) nextText else "") ||
            percentMark != nextMark ||
            percentLetterSpacing != nextLetterSpacing ||
            percentMarkLetterSpacing != nextMarkLetterSpacing ||
            (activeValue != null && (percentPaint.textSize != activeValue.textSize ||
                percentPaint.typeface != activeValue.typeface ||
                percentPaint.color != activeValue.currentTextColor)) ||
            (below && drawValue && activeValue == null && percentPaint.color != icon.foreground) ||
            (activeMark != null && (percentMarkPaint.textSize != activeMark.textSize ||
                percentMarkPaint.typeface != activeMark.typeface ||
                percentMarkPaint.color != activeMark.currentTextColor))
        if (activeValue != null) {
            percentPaint.set(activeValue.paint)
            percentPaint.color = activeValue.currentTextColor
            percentText = nextText
            percentLetterSpacing = nextLetterSpacing
        } else if (below && drawValue) {
            percentPaint.textSize = 12f * resources.displayMetrics.density *
                resources.configuration.fontScale
            percentPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            percentPaint.color = icon.foreground
            percentText = nextText
            percentLetterSpacing = 0f
        } else {
            percentText = ""
            percentLetterSpacing = 0f
        }
        if (activeMark != null) {
            percentMarkPaint.set(activeMark.paint)
            percentMarkPaint.color = activeMark.currentTextColor
            percentMark = nextMark
            percentMarkLetterSpacing = nextMarkLetterSpacing
            percentMarkVerticalOffset = (activeMark.paddingTop - activeMark.paddingBottom) / 2f
        } else {
            percentMark = ""
            percentMarkLetterSpacing = 0f
            percentMarkVerticalOffset = 0f
            if (below && drawValue) {
                percentMarkPaint.set(percentPaint)
                percentMarkPaint.textSize = percentPaint.textSize * 0.72f
                percentMarkLetterSpacing = 0f
            }
        }
        percentBelow = below && drawValue
        percentGapPx = if (percentText.isNotEmpty() && !percentBelow) 2f * density else 0f
        if (oldSlot != leadingPercentSlotWidthPx()) requestLayout()
        if (changed || oldSlot != leadingPercentSlotWidthPx()) invalidate()
    }

    /** Width added before the icon, excluding the view's own start/end padding. */
    fun leadingPercentSlotWidthPx(): Int {
        if (percentText.isEmpty() || percentBelow) return 0
        val width = spacedTextWidth(percentText, percentPaint, percentLetterSpacing) +
            spacedTextWidth(percentMark, percentMarkPaint, percentMarkLetterSpacing) + percentGapPx
        return ceil(width.toDouble()).toInt().coerceAtLeast(0)
    }

    private fun spacedTextWidth(text: String, paint: Paint, letterSpacing: Float): Float =
        if (text.isEmpty()) 0f else paint.measureText(text) +
            letterSpacing * paint.textSize * (text.length - 1).coerceAtLeast(0)

    private fun drawSpacedText(canvas: Canvas, text: String, startX: Float, baseline: Float,
                               paint: Paint, letterSpacing: Float) {
        if (text.isEmpty()) return
        if (letterSpacing == 0f || text.length == 1) {
            canvas.drawText(text, startX, baseline, paint)
            return
        }
        var x = startX
        text.forEachIndexed { index, character ->
            val value = character.toString()
            canvas.drawText(value, x, baseline, paint)
            x += paint.measureText(value)
            if (index < text.lastIndex) x += letterSpacing * paint.textSize
        }
    }

    private fun centeredBaseline(paint: Paint, offset: Float = 0f): Float {
        val metrics = paint.fontMetrics
        return height / 2f - (metrics.ascent + metrics.descent) / 2f + offset
    }

    fun setBatteryOnly(enabled: Boolean, drawable: Drawable?) {
        val nextDrawable = drawable.takeIf { enabled }
        val changed = icon.batteryOnly != enabled || icon.innerBatteryDrawable !== nextDrawable
        icon.batteryOnly = enabled
        icon.innerBatteryDrawable = nextDrawable
        if (changed) invalidate()
    }

    fun submit(content: DuoContent, small5GaEnabled: Boolean, cellularSignal: Picture?) {
        if (icon.content == content && icon.cellularSignalPicture === cellularSignal &&
            icon.small5GaEnabled == small5GaEnabled) return
        icon.small5GaEnabled = small5GaEnabled
        icon.content = content
        icon.cellularSignalPicture = cellularSignal
        val key = DuoRepresentation.of(content)
        contentLayers.getOrPut(key) { DuoDrawable(context) }.apply {
            this.content = content
            cellularSignalPicture = cellularSignal
        }
        if (transition.submit(key, ValueAnimator.areAnimatorsEnabled())) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200L
                interpolator = LinearInterpolator()
                addUpdateListener {
                    transition.advance(it.animatedValue as Float)
                    pruneLayers()
                    invalidate()
                }
                start()
            }
        }
        pruneLayers()
        invalidate()
    }

    private fun pruneLayers() {
        val target = icon.content?.let(DuoRepresentation::of)
        contentLayers.keys.removeAll { it != target && it !in transition.weights }
    }

    fun finishTransition() {
        animator?.cancel()
        animator = null
        transition.finish()
        pruneLayers()
    }

    override fun onDetachedFromWindow() {
        finishTransition()
        super.onDetachedFromWindow()
    }
    override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean = who === icon || super.verifyDrawable(who)
    /** The carrier takes the retained battery's box in full; the drawable fits its glyph. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = DuoLayout.iconSizePx(resources.displayMetrics.density, iconSizeDp)
        // WRAP_CONTENT must report an intrinsic desired size. Feeding MeasureSpec.getSize()
        // back into resolveSize() makes an AT_MOST spec consume the parent's whole allowance.
        // position() aligns the box with the current parent's end edge once active.
        setMeasuredDimension(
            resolveSize(maxOf(desired + paddingStart + paddingEnd + leadingPercentSlotWidthPx(),
                suggestedMinimumWidth), widthMeasureSpec),
            resolveSize(maxOf(desired, suggestedMinimumHeight), heightMeasureSpec)
        )
    }
    override fun onDraw(canvas: Canvas) {
        val iconSide = minOf(height, DuoLayout.iconSizePx(resources.displayMetrics.density, iconSizeDp))
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val iconLeft = if (rtl) paddingLeft else width - paddingRight - iconSide
        icon.setBounds(iconLeft, 0, iconLeft + iconSide, iconSide)
        runCatching {
            // The ring is stable; only network representations crossfade.
            ring.sizes = icon.sizes
            ring.content = icon.content
            ring.foreground = icon.foreground
            ring.bounds = icon.bounds
            ring.draw(canvas)
            transition.weights.forEach { (key, weight) ->
                contentLayers[key]?.apply {
                    sizes = icon.sizes
                    hidePowerTrack = true
                    hiddenSignalRows = icon.hiddenSignalRows
                    hideNetwork = icon.hideNetwork
                    hideAirplane = icon.hideAirplane
                    hideSignalDots = icon.hideSignalDots
                    batteryOnly = icon.batteryOnly
                    innerBatteryDrawable = icon.innerBatteryDrawable
                    bounds = icon.bounds
                    foreground = icon.foreground
                    nativeWifi = icon.nativeWifi
                    small5GaEnabled = icon.small5GaEnabled
                    mirrorAirplane = icon.mirrorAirplane
                    alpha = (weight * 255).roundToInt()
                    draw(canvas)
                }
            }
            if (percentBelow && percentText.isNotEmpty()) {
                drawPercentBelow(canvas, iconLeft, iconSide)
            } else if (percentText.isNotEmpty()) {
                val startX = if (rtl) iconLeft + iconSide + percentGapPx else paddingLeft.toFloat()
                drawSpacedText(canvas, percentText, startX, centeredBaseline(percentPaint),
                    percentPaint, percentLetterSpacing)
                val markX = startX + spacedTextWidth(percentText, percentPaint, percentLetterSpacing)
                drawSpacedText(canvas, percentMark, markX,
                    centeredBaseline(percentMarkPaint, percentMarkVerticalOffset),
                    percentMarkPaint, percentMarkLetterSpacing)
            }
        }.onFailure { error ->
            if (!reportedDrawFailure) {
                reportedDrawFailure = true
                DebugLog.w(TAG, "Duo view draw failed", error)
            }
            drawFailed?.invoke(error)
        }
    }

    private fun drawPercentBelow(canvas: Canvas, iconLeft: Int, iconSide: Int) {
        val valueWidth = spacedTextWidth(percentText, percentPaint, percentLetterSpacing)
        val markWidth = spacedTextWidth(percentMark, percentMarkPaint, percentMarkLetterSpacing)
        val width = valueWidth + markWidth
        val valueMetrics = percentPaint.fontMetrics
        val markMetrics = percentMarkPaint.fontMetrics
        val textHeight = maxOf(valueMetrics.descent - valueMetrics.ascent,
            markMetrics.descent - markMetrics.ascent)
        if (width <= 0f || textHeight <= 0f) return
        val scale = minOf(1f, iconSide * PERCENT_BELOW_MAX_WIDTH_FRACTION / width,
            iconSide * PERCENT_BELOW_MAX_HEIGHT_FRACTION / textHeight)
        val save = canvas.save()
        try {
            canvas.translate(iconLeft + iconSide / 2f, iconSide * PERCENT_BELOW_CENTER_Y)
            canvas.scale(scale * icon.sizes.percent, scale * icon.sizes.percent)
            val startX = -width / 2f
            val baseline = -(valueMetrics.ascent + valueMetrics.descent) / 2f
            drawSpacedText(canvas, percentText, startX, baseline, percentPaint, percentLetterSpacing)
            val markX = startX + valueWidth
            drawSpacedText(canvas, percentMark, markX,
                baseline + percentMarkVerticalOffset, percentMarkPaint, percentMarkLetterSpacing)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private companion object {
        const val TAG = "DuoSignal"
        const val PERCENT_BELOW_MAX_WIDTH_FRACTION = 0.72f
        const val PERCENT_BELOW_MAX_HEIGHT_FRACTION = 0.42f
        const val PERCENT_BELOW_CENTER_Y = 0.84f
    }
}
