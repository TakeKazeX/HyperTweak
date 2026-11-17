package com.takekazex.hypertweak.hook.rules.systemui.slider

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * The host's own look of one view, captured once before the module's first write and the only
 * thing restore reads. Text fields exist for TextViews; geometry (alpha, background, padding,
 * layout) is captured only for popup views, whose layout the module also moves.
 */
internal class LabelBaseline(view: View, private val geometry: Boolean) {
    private val visibility = view.visibility
    private val alpha = view.alpha
    private val background: Drawable? = view.background
    private val tint: ColorStateList? = view.backgroundTintList
    private val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
    var topMargin: Int? = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin
    private val marginEnd = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd
    private val width = view.layoutParams?.width
    private val height = view.layoutParams?.height

    private val text = (view as? TextView)?.let { TextBaseline(it) }

    /** Typography over the host size; a different host write becomes the new base size. */
    class TextBaseline(view: TextView) {
        val text: CharSequence = view.text
        val colors: ColorStateList = view.textColors
        val typeface: Typeface? = view.typeface
        val variation: String? = view.fontVariationSettings
        val activated = view.isActivated
        val selected = view.isSelected
        var hostSize = view.textSize
        var appliedSize = view.textSize
        var face: Typeface? = null
        var weight = 0
        /** The module blur state last written; unknown until the first style pass. */
        var blend = BLEND_UNKNOWN
    }

    val textBaseline: TextBaseline? get() = text

    fun restore(view: View) {
        if (view is TextView) text?.let { base ->
            view.fontVariationSettings = base.variation
            view.typeface = base.typeface
            // Leave a size the host wrote after us; it already owns the label again.
            if (abs(view.textSize - base.appliedSize) <= SIZE_EPSILON && abs(view.textSize - base.hostSize) > SIZE_EPSILON) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base.hostSize)
            }
            view.text = base.text
            view.setTextColor(base.colors)
            view.isActivated = base.activated
            view.isSelected = base.selected
        }
        view.clearMiBlur()
        if (geometry) {
            view.alpha = alpha
            view.background = background
            view.backgroundTintList = tint
            view.setPadding(padding[0], padding[1], padding[2], padding[3])
            view.layoutParams?.let { params ->
                width?.let { params.width = it }
                height?.let { params.height = it }
                if (params is ViewGroup.MarginLayoutParams) {
                    topMargin?.let { params.topMargin = it }
                    marginEnd?.let { params.marginEnd = it }
                }
                view.layoutParams = params
            }
        }
        if (view.visibility != visibility) view.visibility = visibility
    }

    companion object {
        const val SIZE_EPSILON = 0.5f
        const val BLEND_UNKNOWN = 0
        const val BLEND_CLEARED = 1
        const val BLEND_UNIFIED = 2
    }
}

/** Main-thread registry shared by both surfaces; one baseline per view for the generation. */
internal class PercentLabels {
    private val baselines = WeakHashMap<View, LabelBaseline>()

    fun capture(view: View, geometry: Boolean): LabelBaseline =
        baselines.getOrPut(view) { LabelBaseline(view, geometry) }

    operator fun get(view: View): LabelBaseline? = baselines[view]

    fun restore(view: View) {
        baselines.remove(view)?.restore(view)
    }

    fun restoreAll() {
        baselines.entries.toList().forEach { (view, baseline) -> runCatching { baseline.restore(view) } }
        baselines.clear()
    }
}
