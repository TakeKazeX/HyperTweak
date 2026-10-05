package com.takekazex.hypertweak.hook.rules.slider

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.ACTIVE_BLUE_COLOR
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.getSliderTextColor
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.putTag
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method

/**
 * Material treatment of the collapsed volume-dialog percentage badge.
 *
 * 材质风格 has two system modes — 清透磨砂 (`material_style=0`, classic blur) and 柔光玻璃
 * (`material_style=1`, bionics) — and the badge is the small percentage capsule floating above the
 * side volume panel, so it has to be painted from the same material state as the panel surface it
 * sits on rather than a fixed colour.
 */
internal enum class VolumeBadgeSurface {
    /** 柔光玻璃: the volume panel's bionics glass token paints the capsule. */
    GLASS,

    /** 清透磨砂 (or bionics while power save suppresses glass): the panel's blend colour does. */
    FROST,

    /** Background material is off: the neutral capsule keeps the percentage readable. */
    FLAT,
}

internal object VolumeBadgePolicy {
    /**
     * [advancedMaterialEffective] and [glassEnabled] mirror what the host itself asks
     * (`Util.isAdvancedMaterialEffective` / `Util.isBionicsAdvancedMaterialEnabled`), so the badge
     * follows whichever style the volume panel around it is using. Bionics with glass suppressed by
     * power save falls through to [VolumeBadgeSurface.FROST], exactly like the panel's own surfaces.
     */
    fun surface(advancedMaterialEffective: Boolean, glassEnabled: Boolean): VolumeBadgeSurface = when {
        !advancedMaterialEffective -> VolumeBadgeSurface.FLAT
        glassEnabled -> VolumeBadgeSurface.GLASS
        else -> VolumeBadgeSurface.FROST
    }

    /**
     * Whether a collapsed volume column may show its percentage. The expanded dialog always shows
     * it; the control-center main page shows it only in the default style, because that page
     * supplies its own percentage styling for 统一风格.
     */
    fun visible(expanded: Boolean, inControlCenterMainPage: Boolean, sameStyle: Boolean): Boolean =
        expanded || (inControlCenterMainPage && !sameStyle)
}

/**
 * Paints the collapsed percentage badge from the volume panel's current material state.
 *
 * The host paints its own volume-panel surfaces through `Util.setMiViewBackgroundStyle`, which
 * delegates to `miui.systemui.util.MiBackgroundStyle` and picks the bionics glass token for
 * 柔光玻璃 or the blend token for 清透磨砂. The badge reuses that single funnel with the volume
 * panel's own *collapsed slider* tokens (`VolumeColumnRes.getSliderBlendColor` + the collapsed glass
 * token), so the capsule reads as a small mate of the panel strip it floats above, in whichever
 * style — and in any future host style — the panel itself is using. The host's own badge recipe is
 * blend-only and never requests the glass token, which is why the badge stopped following 柔光玻璃
 * before this styler existed.
 *
 * Every host contract is resolved lazily, cached per host class loader (a hot reload replaces the
 * loader and [clear] drops the stale handles), and missing on unsupported builds: a missing
 * contract degrades the capsule to [VolumeBadgeSurface.FLAT] instead of failing the volume panel.
 */
internal class VolumeBadgeStyler(private val tag: String) {
    private companion object {
        const val HOST_UTIL = "com.android.systemui.miui.volume.Util"
        const val HOST_VOLUME_COLUMN_RES = "com.android.systemui.miui.volume.VolumeColumnRes"
        const val HOST_BACKGROUND_STYLE = "miui.systemui.util.MiBackgroundStyle"
        const val HOST_BLUR_COMPAT = "miui.systemui.util.MiBlurCompat"

        const val METHOD_ADVANCED_MATERIAL = "isAdvancedMaterialEffective"
        const val METHOD_GLASS_ENABLED = "isBionicsAdvancedMaterialEnabled"
        const val METHOD_BACKGROUND_STYLE = "setMiViewBackgroundStyle"
        const val METHOD_SLIDER_BLEND_COLOR = "getSliderBlendColor"
        const val METHOD_RADIUS = "getRadius"
        const val METHOD_BLUR_MODE = "setMiViewBlurModeCompat"
        const val METHOD_COLLAPSED_GLASS_TOKEN = "getVOLUMPANEL_COLLAPSED_CLOSED_GLASS_TOKEN"

        /** Blur mode the collapsed volume-panel surfaces use (see the host's `VolumeColumn`). */
        const val COLLAPSED_BLUR_MODE = 1

        /** Fallback capsule radius when the host resource is unreadable; a pill for the 20dp badge. */
        const val FALLBACK_RADIUS_DP = 20f

        val DARK_BADGE_BG_COLOR = "#3A3A3C".toColorInt()
        val LIGHT_BADGE_BG_COLOR = "#E5E5E5".toColorInt()
        val DARK_BADGE_STROKE_COLOR = "#33FFFFFF".toColorInt()
        val LIGHT_BADGE_STROKE_COLOR = "#33000000".toColorInt()
    }

    private class HostHandles(
        val advancedMaterialEffective: Method?,
        val glassEnabled: Method?,
        val backgroundStyle: Method?,
        val backgroundStyleInstance: Any?,
        val sliderBlendColor: Method?,
        val radius: Method?,
        val blurMode: Method?,
        val collapsedGlassToken: Method?,
    )

    @Volatile
    private var cachedLoader: ClassLoader? = null

    @Volatile
    private var cachedHandles: HostHandles? = null

    @Volatile
    private var lastSurface: VolumeBadgeSurface? = null

    fun clear() {
        cachedLoader = null
        cachedHandles = null
        lastSurface = null
    }

    /**
     * Applies the capsule background to the badge's outer view. Returns the treatment that actually
     * landed, which is [VolumeBadgeSurface.FLAT] whenever a host contract is unavailable.
     */
    fun applyCapsule(
        background: View,
        expanded: Boolean,
        hostLoader: ClassLoader,
    ): VolumeBadgeSurface {
        val context = background.context
        val handles = handles(hostLoader)
        val radius = capsuleRadius(context, handles)

        val advancedMaterial = invokeBoolean(handles.advancedMaterialEffective, context)
        val glassEnabled = advancedMaterial && invokeBoolean(handles.glassEnabled, context)
        val requested = VolumeBadgePolicy.surface(advancedMaterial, glassEnabled)

        val materialApplied = requested != VolumeBadgeSurface.FLAT &&
            paintMaterial(background, handles, expanded, requested, radius)
        if (!materialApplied) {
            paintNeutralCapsule(background, context, radius)
        }

        val applied = if (materialApplied) requested else VolumeBadgeSurface.FLAT
        if (applied != lastSurface) {
            lastSurface = applied
            DebugLog.d(tag, "volume badge material=$applied")
        }
        return applied
    }

    /** Bold theme colour, or the slider accent while 统一风格 is on. */
    fun applyTextStyle(text: TextView, sameStyle: Boolean) {
        text.typeface = Typeface.DEFAULT_BOLD
        val color = if (sameStyle) ACTIVE_BLUE_COLOR else getSliderTextColor(text.context)
        if (sameStyle) {
            putTag(text, "sliderType", "VolumePanelViewController")
        }
        withColorOverride {
            runCatching { text.setTextColor(color) }
        }
    }

    /**
     * Drops the text view's own background so only the outer capsule paints the material, keeping
     * the layout padding the capsule relies on.
     */
    fun detachTextBackground(text: TextView) {
        val paddingLeft = text.paddingLeft
        val paddingTop = text.paddingTop
        val paddingRight = text.paddingRight
        val paddingBottom = text.paddingBottom
        text.background = null
        text.backgroundTintList = null
        text.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
    }

    private fun paintMaterial(
        view: View,
        handles: HostHandles,
        expanded: Boolean,
        surface: VolumeBadgeSurface,
        radius: Float,
    ): Boolean {
        val blendToken = invokeStatic(
            handles.sliderBlendColor,
            expanded,
            false,
            surface == VolumeBadgeSurface.GLASS,
        ) ?: return false
        val glassToken = if (surface == VolumeBadgeSurface.GLASS) {
            invokeOnInstance(handles.collapsedGlassToken, handles.backgroundStyleInstance) ?: return false
        } else {
            null
        }

        // Transparent rounded background: the blur outline follows the drawable, like the host's
        // own miui_super_volume_view_transparent.
        view.background = transparentCapsule(radius)
        view.backgroundTintList = null
        invokeStatic(handles.blurMode, view, COLLAPSED_BLUR_MODE)
        return invokeStaticUnit(handles.backgroundStyle, view, COLLAPSED_BLUR_MODE, blendToken, glassToken)
    }

    private fun paintNeutralCapsule(view: View, context: Context, radius: Float) {
        val isDark = isDarkTheme(context)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (isDark) DARK_BADGE_BG_COLOR else LIGHT_BADGE_BG_COLOR)
            cornerRadius = radius
            setStroke(
                context.resources.displayMetrics.density.toInt().coerceAtLeast(1),
                if (isDark) DARK_BADGE_STROKE_COLOR else LIGHT_BADGE_STROKE_COLOR,
            )
        }
        view.backgroundTintList = null
    }

    private fun transparentCapsule(radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.TRANSPARENT)
        cornerRadius = radius
    }

    private fun capsuleRadius(context: Context, handles: HostHandles): Float {
        val fromHost = invokeStatic(handles.radius, context) as? Int
        return fromHost?.toFloat() ?: (FALLBACK_RADIUS_DP * context.resources.displayMetrics.density)
    }

    private fun isDarkTheme(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun invokeBoolean(method: Method?, vararg args: Any?): Boolean =
        invokeStatic(method, *args) as? Boolean ?: false

    private fun invokeStatic(method: Method?, vararg args: Any?): Any? =
        method?.let { runCatching { it.invoke(null, *args) }.getOrNull() }

    /** For host methods returning nothing, where a successful call is the only signal. */
    private fun invokeStaticUnit(method: Method?, vararg args: Any?): Boolean {
        if (method == null) return false
        return runCatching { method.invoke(null, *args) }.isSuccess
    }

    private fun invokeOnInstance(method: Method?, instance: Any?): Any? {
        if (method == null || instance == null) return null
        return runCatching { method.invoke(instance) }.getOrNull()
    }

    /**
     * Resolves the host contracts once per host class loader. The host loader is stable for the
     * lifetime of a hooker, so a plain cache plus [clear] on hot reload is enough; a different loader
     * (plugin re-attach) re-resolves instead of reusing stale handles.
     */
    private fun handles(hostLoader: ClassLoader): HostHandles {
        cachedHandles?.let { if (cachedLoader === hostLoader) return it }
        return synchronized(this) {
            cachedHandles?.let { if (cachedLoader === hostLoader) return@synchronized it }
            resolveHandles(hostLoader).also {
                cachedLoader = hostLoader
                cachedHandles = it
            }
        }
    }

    private fun resolveHandles(hostLoader: ClassLoader): HostHandles {
        val backgroundStyleClass = loadClass(hostLoader, HOST_BACKGROUND_STYLE)
        return HostHandles(
            advancedMaterialEffective = publicMethod(hostLoader, HOST_UTIL, METHOD_ADVANCED_MATERIAL, Context::class.java),
            glassEnabled = publicMethod(hostLoader, HOST_UTIL, METHOD_GLASS_ENABLED, Context::class.java),
            backgroundStyle = publicMethodByNameAndArity(hostLoader, HOST_UTIL, METHOD_BACKGROUND_STYLE, 4),
            backgroundStyleInstance = backgroundStyleClass?.let {
                runCatching { it.getField("INSTANCE").get(null) }.getOrNull()
            },
            sliderBlendColor = publicMethodByNameAndArity(hostLoader, HOST_VOLUME_COLUMN_RES, METHOD_SLIDER_BLEND_COLOR, 3),
            radius = publicMethod(hostLoader, HOST_VOLUME_COLUMN_RES, METHOD_RADIUS, Context::class.java),
            blurMode = publicMethodByNameAndArity(hostLoader, HOST_BLUR_COMPAT, METHOD_BLUR_MODE, 2),
            collapsedGlassToken = publicMethodByNameAndArity(hostLoader, HOST_BACKGROUND_STYLE, METHOD_COLLAPSED_GLASS_TOKEN, 0),
        )
    }

    private fun loadClass(classLoader: ClassLoader, className: String): Class<*>? =
        runCatching { classLoader.loadClass(className) }.getOrNull()

    private fun publicMethod(
        classLoader: ClassLoader,
        className: String,
        name: String,
        vararg parameterTypes: Class<*>,
    ): Method? = runCatching {
        loadClass(classLoader, className)?.getMethod(name, *parameterTypes)
    }.getOrNull()

    /**
     * Host signatures carry plugin/theme types (`ColorBlendToken`, `BionicsToken`) that cannot be
     * named from the module's class loader, so those lookups match on name and arity instead.
     */
    private fun publicMethodByNameAndArity(
        classLoader: ClassLoader,
        className: String,
        name: String,
        arity: Int,
    ): Method? = runCatching {
        loadClass(classLoader, className)
            ?.methods
            ?.firstOrNull { it.name == name && it.parameterTypes.size == arity }
    }.getOrNull()
}
