package com.takekazex.hypertweak.hook.rules.systemui.volume.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView

/**
 * One app's gain slider, laid out like the native collapsed volume column: a material track,
 * a clipped fill and the app icon at the bottom. Dragging is relative to the touch-down value,
 * as on the native column, so touching a column never makes the gain jump.
 */
@SuppressLint("ViewConstructor")
internal class AppVolumeColumn(
    context: Context,
    private val host: AppVolumeHost,
    val key: String,
    private val callbacks: Callbacks,
) : FrameLayout(context) {
    interface Callbacks {
        fun onGain(column: AppVolumeColumn, gain: Float, final: Boolean)
        fun onInteraction()
    }

    private val track = View(context)
    private val clip = FrameLayout(context)
    private val fill = View(context)
    private val icon = ImageView(context)
    /** Inner percentage, at the column top like the native super-volume inner label. */
    val percent = TextView(context)
    private var trackHeight = 1
    private var percentTrackColor = percent.currentTextColor
    private var percentFillColor = percent.currentTextColor
    private var boundApp: AppVolumeAudio.App? = null
    private var hostScaleX = 1f
    private var hostScaleY = 1f
    private var hostStretchY = 0f
    private var overscroll = 0f
    private var rebound: ValueAnimator? = null
    private var downY = 0f
    private var downGain = 0f
    private var dragging = false
    private var label: CharSequence = key

    var gain = 1f
        private set

    init {
        clipChildren = false
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(track)
        clip.addView(fill)
        addView(clip)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        icon.setImageDrawable(context.packageManager.defaultActivityIcon)
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon)
        percent.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        percent.gravity = Gravity.CENTER
        percent.includeFontPadding = false
        percent.isSingleLine = true
        percent.visibility = GONE
        addView(percent)
        applyHostStyle()
    }

    /** Sizes and materials from the host helpers; rerun after configuration or material changes. */
    fun applyHostStyle() {
        val width = host.columnWidth(context)
        trackHeight = host.columnHeight(context).coerceAtLeast(1)
        val radius = host.columnRadius(context).toFloat()
        val rounded = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        listOf(track, clip).forEach { it.layoutParams = LayoutParams(width, trackHeight) }
        fill.layoutParams = LayoutParams(width, trackHeight)
        clip.outlineProvider = rounded
        clip.clipToOutline = true
        track.outlineProvider = rounded
        host.styleTrack(track, radius)
        if (!host.styleFill(fill)) {
            fill.background = GradientDrawable().apply { setColor(FALLBACK_FILL) }
        }
        val iconSize = host.columnIconSize(context)
        icon.layoutParams = LayoutParams(iconSize, iconSize, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = host.columnIconMargin(context)
        }
        percent.setTextSize(TypedValue.COMPLEX_UNIT_PX, host.percentTextSize(context))
        percent.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (PERCENT_TOP_DP * resources.displayMetrics.density).toInt() }
        render(gain)
    }

    var showInnerPercent = false
        set(value) { field = value; percent.visibility = if (value) VISIBLE else GONE }

    val iconColor: Int? get() = boundApp?.iconColor

    fun bind(app: AppVolumeAudio.App?, gain: Float?): Boolean {
        val changed = boundApp !== app
        if (changed) {
            boundApp = app
            label = app?.label ?: key
            icon.setImageDrawable(app?.icon ?: context.packageManager.defaultActivityIcon)
        }
        if (gain != null && !dragging) render(gain)
        return changed
    }

    fun setPercentColors(track: Int, fill: Int) {
        percentTrackColor = track
        percentFillColor = fill
        updatePercentColor()
    }

    private fun updatePercentColor() {
        if (!showInnerPercent) return
        val center = percent.top + percent.height / 2f
        val color = if (center >= fill.translationY) percentFillColor else percentTrackColor
        if (percent.currentTextColor != color) percent.setTextColor(color)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updatePercentColor()
    }

    fun followHostStretch(source: View) {
        hostScaleX = source.scaleX
        hostScaleY = source.scaleY
        hostStretchY = source.translationY
        updateStretch()
    }

    private fun updateStretch() {
        val pull = overscroll / (1f + kotlin.math.abs(overscroll)) * 0.05f
        scaleX = hostScaleX * (1f + pull)
        scaleY = hostScaleY * (1f + pull)
        translationY = hostStretchY - pull * trackHeight
    }

    private fun releaseStretch() {
        if (overscroll == 0f) return
        rebound = ValueAnimator.ofFloat(overscroll, 0f).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                overscroll = it.animatedValue as Float
                updateStretch()
            }
            start()
        }
    }

    fun resetStretch() {
        rebound?.cancel()
        rebound = null
        overscroll = 0f
        hostScaleX = 1f
        hostScaleY = 1f
        hostStretchY = 0f
        if (dragging) {
            dragging = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        updateStretch()
    }

    override fun onDetachedFromWindow() {
        resetStretch()
        super.onDetachedFromWindow()
    }

    private fun render(value: Float) {
        gain = value.coerceIn(0f, 1f)
        fill.translationY = trackHeight * (1f - gain)
        updatePercentColor()
        val text = "${AppVolumeCandidates.percent(gain)}%"
        if (percent.text?.toString() != text) percent.text = text
        contentDescription = "$label ${AppVolumeCandidates.percent(gain)}%"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                rebound?.cancel()
                rebound = null
                overscroll = 0f
                updateStretch()
                downY = event.rawY
                downGain = gain
                dragging = true
                callbacks.onInteraction()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val requested = downGain + (downY - event.rawY) / trackHeight
                val next = requested.coerceIn(0f, 1f)
                overscroll = requested - next
                updateStretch()
                if (AppVolumeCandidates.percent(next) != AppVolumeCandidates.percent(gain) ||
                    (next != gain && (next == 0f || next == 1f))) {
                    val edge = next == 0f || next == 1f
                    render(next)
                    if (edge) performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                    callbacks.onGain(this, gain, final = false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                releaseStretch()
                parent?.requestDisallowInterceptTouchEvent(false)
                callbacks.onGain(this, gain, final = true)
            }
        }
        return true
    }

    override fun getAccessibilityClassName(): CharSequence = SeekBar::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0f, 100f, gain * 100f)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val next = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> gain + ACCESSIBILITY_STEP
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> gain - ACCESSIBILITY_STEP
            android.R.id.accessibilityActionSetProgress ->
                arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.div(100f)
            else -> null
        } ?: return super.performAccessibilityAction(action, arguments)
        render(next)
        callbacks.onGain(this, gain, final = true)
        return true
    }

    private companion object {
        const val FALLBACK_FILL = 0xF2FFFFFF.toInt()
        const val ACCESSIBILITY_STEP = 0.1f
        /** Native inner labels sit 12dp lower in the collapsed bar when the capsule is inside. */
        const val PERCENT_TOP_DP = 12f
    }
}
