package com.takekazex.hypertweak.hook.rules.systemui.slider

import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.presentation.slider.SliderPercentageColors
import com.takekazex.hypertweak.settings.contract.SliderPercentagePolicy
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.ResourceLookup
import kotlin.math.abs

internal enum class LabelKind(val brightness: Boolean) { BRIGHTNESS(true), VOLUME(false), EXPANDED_BRIGHTNESS(true) }

/**
 * Colour, blur blend and typography of every percentage label. Each surface writes a label only
 * through [controlCenter] or [popup], and typography always runs last, so no other path can
 * leave a label with the host font after the module styled it.
 */
internal class PercentLabelStyle(private val labels: PercentLabels, private val hostLoader: ClassLoader) {
    private val activeColors = HashMap<LabelKind, Int>()
    private val forcedBlendColors = HashMap<Int, IntArray>()
    private var blend: Pair<Resources, Int>? = null
    private val typefaces = HashMap<Pair<Typeface, Int>, Typeface>()

    fun clear() {
        activeColors.clear()
        forcedBlendColors.clear()
        blend = null
        typefaces.clear()
    }

    /**
     * [force] re-applies the blend: the host has just cleared blend effects. Otherwise the blur
     * state is written once per label and mode, since this runs on every drag frame. [covered]
     * tells whether the slider's white fill reaches the label: there the host's own top-text
     * colour is used (it is drawn for that fill), above the fill a light text reads on the track.
     */
    fun controlCenter(label: TextView, kind: LabelKind, policy: SliderPercentagePolicy, force: Boolean, covered: Boolean) {
        val state = labels[label]?.textBaseline
        val blend = if (policy.unifiedColor) LabelBaseline.BLEND_UNIFIED else LabelBaseline.BLEND_CLEARED
        val writeBlend = force || state?.blend != blend
        val color = if (!policy.unifiedColor) {
            if (writeBlend) label.clearMiBlur()
            label.isActivated = state?.activated ?: false
            label.isSelected = state?.selected ?: false
            if (covered) state?.colors?.defaultColor ?: SliderPercentageColors.CC_ON_FILL else SliderPercentageColors.CC_ON_TRACK
        } else {
            val active = activeColor(label.context, kind)
            label.isActivated = true
            label.isSelected = true
            if (writeBlend) applyBlend(label, active)
            active
        }
        state?.blend = blend
        if (label.currentTextColor != color || label.textColors.isStateful) label.setTextColor(color)
        typography(label, policy, Typeface.DEFAULT)
    }

    private fun applyBlend(label: TextView, active: Int) {
        label.clearMiBlur()
        val context = label.context
        val source = (if (blurSupported(context)) blendResources(context) else null) ?: return
        label.setMiViewBlurMode(3)
        runCatching {
            val colors = forcedBlendColors.getOrPut(active) { forceBlend(source.first.getIntArray(source.second), active) }
            blendSetter?.invoke(null, label, colors, 1f)
        }.onFailure {
            DebugLog.w(TAG, "Cannot apply control-center percentage blend", it)
            label.clearMiBlur()
        }
    }

    fun popup(label: TextView, policy: SliderPercentagePolicy) {
        val color = if (policy.unifiedColor) ACTIVE_BLUE else SliderPercentageColors.popup(VolumeBadgePolicy.isDark(label.context)).text
        if (label.currentTextColor != color || label.textColors.isStateful) label.setTextColor(color)
        typography(label, policy, POPUP_TYPEFACE)
    }

    /** Weight over [base], size relative to the host size; defaults while the custom option is off. */
    private fun typography(label: TextView, policy: SliderPercentagePolicy, base: Typeface) {
        val state = labels[label]?.textBaseline
        val config = policy.typography.effective()
        val current = label.textSize
        if (state != null && abs(current - state.appliedSize) > LabelBaseline.SIZE_EPSILON) state.hostSize = current
        val target = (state?.hostSize ?: current) * config.sizePercent / 100f
        if (state != null && abs(current - target) > LabelBaseline.SIZE_EPSILON) label.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
        state?.appliedSize = label.textSize

        if (state == null || label.typeface !== state.face || state.weight != config.weight) {
            label.typeface = typefaces.getOrPut(base to config.weight) { Typeface.create(base, config.weight, false) }
            // Variable system fonts take the exact weight; static fonts keep the nearest family member.
            label.fontVariationSettings = "'wght' ${config.weight}"
            state?.face = label.typeface
            state?.weight = config.weight
        }
    }

    private fun activeColor(context: Context, kind: LabelKind): Int = activeColors.getOrPut(kind) {
        runCatching {
            if (kind == LabelKind.VOLUME) {
                val systemUi = ResourceLookup.packageContext(context, "com.android.systemui")
                val id = systemUi?.let { ResourceLookup.identifier(it, "miui_volume_icon_color_blue", "color", "com.android.systemui") } ?: 0
                return@runCatching if (id != 0 && systemUi != null) systemUi.getColor(id) else ACTIVE_BLUE
            }
            val id = ResourceLookup.identifier(context, "toggle_slider_active_color", "color", context.packageName)
            if (id == 0) return@runCatching FALLBACK_ORANGE
            mostSaturated(context.resources.getColorStateList(id, context.theme)) ?: FALLBACK_ORANGE
        }.getOrDefault(FALLBACK_ORANGE)
    }

    /** The selector's tinted state; the default state is usually neutral. */
    private fun mostSaturated(colors: ColorStateList): Int? {
        val hsv = FloatArray(3)
        return (listOf(colors.defaultColor) + ACTIVE_STATES.map { colors.getColorForState(it, colors.defaultColor) })
            .maxByOrNull { Color.colorToHSV(it, hsv); hsv[1] }
            ?.takeIf { Color.colorToHSV(it, hsv); hsv[1] >= 0.15f }
    }

    private fun forceBlend(original: IntArray, active: Int): IntArray {
        val hsv = FloatArray(3)
        return IntArray(original.size) { index ->
            val color = original[index]
            Color.colorToHSV(color, hsv)
            if (hsv[1] > 0.3f) Color.argb(Color.alpha(color), Color.red(active), Color.green(active), Color.blue(active)) else color
        }
    }

    private fun blendResources(context: Context): Pair<Resources, Int>? {
        blend?.let { return it.takeIf { it.second != 0 } }
        var resources = context.resources
        var id = ResourceLookup.identifier(context, "toggle_slider_icon_blend_colors", "array", context.packageName)
        if (id == 0) {
            ResourceLookup.packageResources(context, PLUGIN)?.let { plugin ->
                ResourceLookup.identifier(plugin, "toggle_slider_icon_blend_colors", "array", PLUGIN).takeIf { it != 0 }?.let {
                    resources = plugin
                    id = it
                }
            }
        }
        blend = resources to id
        return blend?.takeIf { id != 0 }
    }

    // Runs on every drag frame: resolve the two static host helpers once per generation.
    private val blurQuery by lazy(LazyThreadSafetyMode.NONE) {
        staticMethod("miui.systemui.controlcenter.utils.ControlCenterUtils", "getBackgroundBlurOpenedInDefaultTheme", Context::class.java)
    }
    private val blendSetter by lazy(LazyThreadSafetyMode.NONE) {
        staticMethod("miui.systemui.util.MiBlurCompat", "setMiBackgroundBlendColors",
            View::class.java, IntArray::class.java, Float::class.javaPrimitiveType!!)
    }

    private fun staticMethod(className: String, name: String, vararg types: Class<*>) = runCatching {
        HostAccess.publicMethod(hostLoader.loadClass(className), name, *types, accessible = false)
    }.onFailure { DebugLog.w(TAG, "Missing $className.$name", it) }.getOrNull()

    private fun blurSupported(context: Context): Boolean =
        runCatching { blurQuery?.invoke(null, context) as? Boolean }.getOrNull() ?: false

    companion object {
        private const val TAG = "SliderPercentage"
        private const val PLUGIN = "miui.systemui.plugin"
        val ACTIVE_BLUE = 0xFF3482FF.toInt()
        private val FALLBACK_ORANGE = 0xFFFF9800.toInt()
        /** The popup layout declares `android:fontFamily="MiSans-Medium"`. */
        val POPUP_TYPEFACE: Typeface = Typeface.create("MiSans-Medium", Typeface.NORMAL)
        private val ACTIVE_STATES = listOf(
            intArrayOf(android.R.attr.state_activated),
            intArrayOf(android.R.attr.state_selected),
            intArrayOf(android.R.attr.state_activated, android.R.attr.state_selected),
            intArrayOf(android.R.attr.state_checked)
        )
    }
}

// MiBlur view extensions: hidden View APIs present on HyperOS, tolerated when absent.
private fun View.callBlur(name: String, vararg args: Any?) {
    HookFailurePolicy.open("SliderPercentage", "apply label blur", Unit) {
        val types = args.map {
            when (it) { is Int -> Int::class.javaPrimitiveType!!; is Boolean -> Boolean::class.javaPrimitiveType!!; else -> View::class.java }
        }.toTypedArray()
        HostAccess.publicMethod(javaClass, name, *types, accessible = false)?.invoke(this, *args)
    }
}

internal fun View.setMiViewBlurMode(mode: Int) = callBlur("setMiViewBlurMode", mode)

internal fun View.clearMiBlur() {
    callBlur("setMiViewBlurMode", 0)
    callBlur("setMiBackgroundBlurMode", 0)
    callBlur("setMiBackgroundBlurRadius", 0)
    callBlur("setPassWindowBlurEnabled", false)
    callBlur("clearMiBackgroundBlendColor")
}
