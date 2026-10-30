package com.takekazex.hypertweak.hook.rules.slider

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method

internal enum class VolumeBadgeSurface { FLAT, FROST, GLASS }

internal data class VolumeBadgePalette(val background: Int, val stroke: Int, val text: Int)

internal object VolumeBadgePolicy {
    fun surface(style: Int): VolumeBadgeSurface = when (style) {
        1 -> VolumeBadgeSurface.FROST
        2 -> VolumeBadgeSurface.GLASS
        else -> VolumeBadgeSurface.FLAT
    }

    /** Native SRC_OVER blend over the blurred sample, deliberately independent of fixed host tokens. */
    fun frostBlend(dark: Boolean): IntArray = intArrayOf(
        if (dark) 0xB3202020.toInt() else 0xB3FFFFFF.toInt(),
        3 // BlendModeToken.SRC_OVER.value, verified in the current plugin.
    )

    fun palette(dark: Boolean): VolumeBadgePalette = if (dark) {
        VolumeBadgePalette(0xFF3A3A3C.toInt(), 0x33FFFFFF, 0xB3FFFFFF.toInt())
    } else {
        VolumeBadgePalette(0xFFE5E5E5.toInt(), 0x33000000, 0xB3000000.toInt())
    }
}

/** Explicit materials scoped to the percentage view, independent of the host's global mode. */
internal class VolumeBadgeStyler(private val tag: String) {
    private class HostHandles(
        val clearGlass: Method?, val blend: Method?,
        val mode: Method?, val glassToken: Method?, val openGlassToken: Method?,
        val materialType: Method?, val glassParams: Method?, val glassLight: Method?, val glassCompat: Method?, val glassMode: Int?,
        val nativeBlur: Method?, val nativeBlurRadius: Method?, val blurOutline: Method?,
        val labelWidth: Method?, val labelHeight: Int?, val buttonBackground: Method?
    )

    private data class Appearance(
        val style: Int, val night: Int, val density: Int, val expanded: Boolean,
        val drawable: android.graphics.drawable.Drawable?, val applied: VolumeBadgeSurface
    )
    private val appearances = java.util.WeakHashMap<View, Appearance>()
    private val nativeTypeface = Typeface.create("MiSans-Medium", Typeface.NORMAL)
    private var lastSurface: VolumeBadgeSurface? = null
    private var lastNight: Boolean? = null
    fun clear() { lastSurface = null; lastNight = null; appearances.clear() }

    /** Use the super-volume label's dimensions, including when recovering an older generation. */
    fun restoreNativeSize(background: View, loader: ClassLoader) {
        val metadata = handles(loader) ?: return
        val width = call(metadata.labelWidth, null, background.context) as? Int ?: return
        val heightId = metadata.labelHeight ?: return
        val height = background.resources.getDimensionPixelSize(heightId)
        val params = background.layoutParams ?: return
        if (width > 0 && height > 0 && (params.width != width || params.height != height)) {
            params.width = width
            params.height = height
            background.layoutParams = params
        }
    }

    fun applyCapsule(background: View, expanded: Boolean, hostLoader: ClassLoader, style: Int): VolumeBadgeSurface {
        val config = background.resources.configuration
        val night = nightMode(background.context)
        val previous = appearances[background]
        if (previous != null && previous.style == style && previous.night == night &&
            previous.density == config.densityDpi && previous.expanded == expanded && previous.drawable === background.background) {
            return previous.applied
        }
        val handles = handles(hostLoader)
        // Native collapsed buttons use half their own height, not the volume column radius.
        // Apply that same shape rule to the native 20dp label instead of importing a 40dp button.
        val radius = (background.layoutParams?.height?.takeIf { it > 0 } ?: background.height).coerceAtLeast(1) / 2f
        // Release the previous material before switching, including glass -> blur/ordinary.
        clearMaterial(background, handles)
        val requested = VolumeBadgePolicy.surface(style)
        val applied = if (requested != VolumeBadgeSurface.FLAT && paintMaterial(background, handles, requested, radius)) {
            requested
        } else {
            paintNeutralCapsule(background, radius, handles)
            VolumeBadgeSurface.FLAT
        }
        appearances[background] = Appearance(style, night, config.densityDpi, expanded, background.background, applied)
        val dark = night == Configuration.UI_MODE_NIGHT_YES
        if (lastSurface != applied || lastNight != dark) {
            lastSurface = applied
            lastNight = dark
            val params = background.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            DebugLog.i(tag, "volume badge material=$applied dark=$dark size=" + params?.width + "x" + params?.height + " top=" + params?.topMargin + " end=" + params?.marginEnd)
        }
        return applied
    }

    fun restoreNativeFont(text: TextView) {
        if (text.typeface != nativeTypeface) text.typeface = nativeTypeface
    }

    fun invalidateAppearance(view: View) { appearances.remove(view) }

    fun applyTextStyle(text: TextView, sameStyle: Boolean) {
        restoreNativeFont(text)
        val color = if (sameStyle) SliderHookHelper.ACTIVE_BLUE_COLOR else VolumeBadgePolicy.palette(isDark(text.context)).text
        withColorOverride { text.setTextColor(color) }
    }

    fun detachTextBackground(text: TextView) {
        val padding = intArrayOf(text.paddingLeft, text.paddingTop, text.paddingRight, text.paddingBottom)
        clearMaterial(text, handles(text.context.classLoader))
        text.background = null
        text.backgroundTintList = null
        text.setPadding(padding[0], padding[1], padding[2], padding[3])
    }

    fun clearMaterial(view: View) {
        appearances.remove(view)
        clearMaterial(view, handles(view.context.classLoader))
    }

    private fun clearMaterial(view: View, handles: HostHandles?) {
        call(handles?.clearGlass, null, view)
        view.clearMiBlur()
    }

    private fun paintMaterial(view: View, handles: HostHandles?, surface: VolumeBadgeSurface, radius: Float): Boolean {
        if (handles == null) return false
        return runCatching {
            // RingerButtonHelper.applyCollapsedStyle clears the Drawable before applying material.
            view.background = null
            view.backgroundTintList = null
            val outline = handles.blurOutline ?: return false
            outline.invoke(null, view, radius)
            val mode = handles.mode ?: return false
            if (mode.invoke(null, view, 1) != true) return false
            if (surface == VolumeBadgeSurface.FROST) {
                val colors = VolumeBadgePolicy.frostBlend(isDark(view.context))
                val blend = handles.blend ?: return false
                // Same path as MiuiVolumeDialogMotion's native advanced-material attach listener.
                val nativeBlur = handles.nativeBlur ?: return false
                val blurRadius = call(handles.nativeBlurRadius, null, view.context) as? Int ?: return false
                nativeBlur.invoke(null, view, blurRadius, false)
                blend.invoke(null, view, colors)
            } else {
                val nativeStyle = handles.materialType?.declaringClass ?: return false
                val instance = HostAccess.requirePublicField(nativeStyle, "INSTANCE", accessible = false).get(null)
                val token = call(handles.glassToken, instance) ?: return false
                val open = call(handles.openGlassToken, instance) ?: return false
                val light = call(handles.glassLight, instance) ?: return false
                // The native button's progress=0, unpressed branch computes 42 entries, including
                // MAIN_PANEL_LIGHT_PARAMS. getToBionicsParams() only populates the first 37.
                val params = call(handles.glassParams, instance, 0f, token, open, false, 0f, light) as? FloatArray ?: return false
                val material = handles.materialType
                val glassMode = handles.glassMode ?: return false
                val glass = handles.glassCompat ?: return false
                material.invoke(instance, view, glassMode)
                glass.invoke(instance, view, params)
            }
            true
        }.onFailure { DebugLog.w(tag, "Cannot apply volume percentage material=$surface", it) }.getOrDefault(false)
    }

    private fun paintNeutralCapsule(view: View, radius: Float, handles: HostHandles?) {
        // Also clean a partially applied unsupported material before the fallback.
        clearMaterial(view, handles)
        val nativeBackground = call(handles?.buttonBackground, null, false, true, false) as? Int
        if (nativeBackground != null && nativeBackground != 0) {
            // Plugin wrappers can retain a stale night configuration; use current application mode.
            val configuration = Configuration(view.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode(view.context)
            }
            val drawable = runCatching {
                // Keep the plugin's AssetManager: a wrapped Context's base may be SystemUI.
                @Suppress("DEPRECATION")
                val resources = android.content.res.Resources(view.resources.assets, view.resources.displayMetrics, configuration)
                resources.getDrawable(nativeBackground, null)?.mutate()
            }.getOrNull()
            if (drawable != null) {
                // Keep the native button's selector/colours while sizing its capsule to this label.
                (drawable as? GradientDrawable)?.cornerRadius = radius
                view.background = drawable
                view.backgroundTintList = null
                return
            }
        }
        val colors = VolumeBadgePolicy.palette(isDark(view.context))
        view.background = capsule(radius, colors.background).apply {
            setStroke(view.resources.displayMetrics.density.toInt().coerceAtLeast(1), colors.stroke)
        }
        view.backgroundTintList = null
    }

    private fun capsule(radius: Float, color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
    }

    // A themed plugin wrapper may retain its own Configuration. Read the running application's
    // system configuration for both the cache key and the actual blend/text palette.
    private fun nightMode(context: Context): Int =
        (context.applicationContext ?: context).resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    private fun isDark(context: Context) = nightMode(context) == Configuration.UI_MODE_NIGHT_YES
    private fun call(method: Method?, owner: Any?, vararg args: Any?): Any? =
        method?.let { runCatching { it.invoke(owner, *args) }.getOrNull() }

    /** Cached metadata uses exact parameter types from the loaded plugin, never name/arity alone. */
    private fun handles(loader: ClassLoader): HostHandles? = runCatching {
        val style = loader.loadClass("miui.systemui.util.MiBackgroundStyle")
        HostAccess.cached(style, "slider.percentage.materialHandles", HostHandles::class.java) {
            val blur = loader.loadClass("miui.systemui.util.MiBlurCompat")
            val buttons = loader.loadClass("com.android.systemui.miui.volume.RingerButtonRes")
            val util = loader.loadClass("com.android.systemui.miui.volume.Util")
            val dialogRes = loader.loadClass("com.android.systemui.miui.volume.MiuiVolumeDialogRes")
            val bionics = loader.loadClass("miui.systemui.util.BionicsToken")
            val light = loader.loadClass("miui.systemui.util.LightParams")
            val dimensions = loader.loadClass("com.android.systemui.miui.volume.R\$dimen")
            val mode = loader.loadClass("miui.systemui.util.MaterialMode\$Bionics")
            val modeInstance = HostAccess.requirePublicField(mode, "INSTANCE", accessible = false).get(null)
            val glassMode = HostAccess.publicMethod(mode, "getValue", accessible = false)?.invoke(modeInstance) as? Int
            HostHandles(
                HostAccess.publicMethod(style, "clearBionicsMaterial", View::class.java, accessible = false),
                HostAccess.publicMethod(blur, "setMiBackgroundBlendColors", View::class.java, IntArray::class.java, accessible = false),
                HostAccess.publicMethod(blur, "setMiViewBlurModeCompat", View::class.java, Int::class.javaPrimitiveType!!, accessible = false),
                HostAccess.publicMethod(style, "getVOLUMPANEL_COLLAPSED_CLOSED_GLASS_TOKEN", accessible = false),
                HostAccess.publicMethod(style, "getVOLUMPANEL_COLLAPSED_OPEN_GLASS_TOKEN", accessible = false),
                HostAccess.publicMethod(style, "setMiViewMaterialType", View::class.java, Int::class.javaPrimitiveType!!, accessible = false),
                HostAccess.declaredMethod(style, "calculateGlassParams", Float::class.javaPrimitiveType!!, bionics, bionics, Boolean::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!, light, accessible = true),
                HostAccess.publicMethod(style, "getMAIN_PANEL_LIGHT_PARAMS", accessible = false),
                HostAccess.declaredMethod(style, "setMiGlassCompat", View::class.java, FloatArray::class.java, accessible = true),
                glassMode,
                HostAccess.publicMethod(util, "setMiBgBlur", View::class.java, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, accessible = false),
                HostAccess.publicMethod(dialogRes, "getBlandBlurRadius", Context::class.java, accessible = false),
                HostAccess.publicMethod(blur, "setBlurOutlineRoundRect", View::class.java, Float::class.javaPrimitiveType!!, accessible = false),
                HostAccess.publicMethod(dialogRes, "getSuperVolumeWidth", Context::class.java, accessible = false),
                HostAccess.requirePublicField(dimensions, "miui_super_volume_text_height_collapsed", accessible = false).getInt(null),
                HostAccess.publicMethod(buttons, "getButtonBgId", Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, accessible = false)
            )
        }
    }.getOrNull()
}
