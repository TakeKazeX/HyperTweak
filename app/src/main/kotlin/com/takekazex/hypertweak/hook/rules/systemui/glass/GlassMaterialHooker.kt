package com.takekazex.hypertweak.hook.rules.systemui.glass

import com.takekazex.hypertweak.hook.base.orSkip
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.presentation.glass.scaleBlendArrayColors
import com.takekazex.hypertweak.presentation.glass.scaleColorAlpha
import com.takekazex.hypertweak.presentation.glass.scaleColorLightness
import com.takekazex.hypertweak.presentation.glass.scaleGlassParams
import com.takekazex.hypertweak.presentation.glass.scaleRadius

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import android.content.res.AssetManager
import android.content.res.Resources
import android.view.View
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import java.lang.ref.WeakReference
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * OS4 material-style glass tuner (Settings → Display → Visual style, 材质风格).
 *
 * The two system modes — 清透磨砂 (`material_style=0`, `MaterialType.BLUR`) and 柔光玻璃
 * (`material_style=1`, `MaterialType.GLASS`) — differ only by which SystemUI resources they
 * read. Every consumer (`BlurUtilsExt`, `ShadeBlendBlurController`, `KeyguardMoveHelper`,
 * `BlurUtilsImpl`, `ModalWindowManager`, ...) pulls its parameters straight from `Resources`,
 * so intercepting the handful of resources behind both modes overrides every surface at once:
 *
 * - `window_background_blend_colors[_bionics]` / `shade_blend_colors[_bionics]`: blend color
 *   arrays, `[color, blendMode, ...]` pairs, applied with `setMiBackgroundBlendColors`.
 * - `mi_blur_max_radius[_bionics]`, `shade_blur_max_radius`, `combined_blur_max_radius`,
 *   `modal_glass_blur_max_radius`, `notification_glass_small/big_blur_max_radius`: the max
 *   blur radii; the actual radius is `ratio × maxRadius` (`MiBlurCompat.blurRatio2Radius`).
 * - `notification_control_center_blur_dim_color`: the dim scrim alpha behind blurred panels.
 * - The glass-shader arrays fed to `View.setMiGlass(float[])`: every glass renderer funnels
 *   through that hidden View method — the SystemUI APK via `MiGlassCompat.setMiGlassCompat`
 *   (notification cards, media, modal dialogs, lockscreen editor) and the
 *   miui.systemui.plugin via its own `MiBackgroundStyle` (control center, volume and
 *   brightness panels keep their `BionicsToken` glass constants in code, not resources), so
 *   intercepting the View method covers every surface in one place.
 * - Card-level blend shade colors (`*_blend_shade_color_*`) and the plugin's own blend arrays
 *   (`miui_expanded_bg_blend_colors_cc` etc.), both applied on top of the glass.
 *
 * "More transparent" is expressed as multipliers: blend/dim alpha (lower = see more of the
 * wallpaper behind the surface), max blur radius (lower = less frost), and glass-shader
 * opacity (lower = cards less tinted and less highlighted). Accepted settings refresh the
 * shade's resource owners and replay the original shader parameters on existing views.
 */
object GlassMaterialHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingGlassTunerEnabled by hookSetting(false) { Preferences.getBoolean(SystemUiSettingsKeys.KEY_GLASS_TUNER_ENABLED, false) }
    private val settingGlassTunerBlendAlpha by hookSetting(1f) {
        Preferences.getFloat(SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_ALPHA, 1f)
    }
    private val settingGlassTunerBlendLightness by hookSetting(1f) {
        Preferences.getFloat(SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_LIGHTNESS, 1f)
    }
    private val settingGlassTunerRadiusScale by hookSetting(1f) {
        Preferences.getFloat(SystemUiSettingsKeys.KEY_GLASS_TUNER_RADIUS_SCALE, 1f)
    }
    private val settingGlassTunerGlassOpacity by hookSetting(1f) {
        Preferences.getFloat(SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_OPACITY, 1f)
    }
    private val settingGlassTunerGlassTone by hookSetting(1f) { Preferences.getFloat(SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_TONE, 1f) }

    private const val TAG = "GlassMaterial"
    private const val SHADE_BLUR = "com.miui.systemui.shade.blur.ShadeBlendBlurControllerImpl"
    private const val BLUR_UTILS = "com.miui.systemui.statusbar.policy.BlurUtilsExt"

    init {
        hostRecovery(HostRecoveryPhase.CONTENT) { scope ->
            val component = scope.component ?: return@hostRecovery
            runCatching {
                val shade = HostAccess.provider(component, "provideShadeBlendBlurControllerProvider")
                if (shade?.javaClass?.name == SHADE_BLUR) shadeBlur = WeakReference(shade)
                val utils = HostAccess.provider(component, "blurUtilsExtProvider")
                if (utils?.javaClass?.name == BLUR_UTILS) blurUtils = WeakReference(utils)
                if (enabled) refreshHostResources()
            }.onFailure { DebugLog.w(TAG, "Material host recovery failed", it) }
        }
    }

    /** Blend color arrays ([color, blendMode, ...] pairs) behind the two material modes. */
    private val BLEND_ARRAYS = setOf(
        "window_background_blend_colors",
        "window_background_blend_colors_bionics",
        "shade_blend_colors",
        "shade_blend_colors_bionics"
    )

    /** Max blur radius dimens consumed by the status bar / shade / control center / keyguard. */
    private val RADIUS_DIMENS = setOf(
        "mi_blur_max_radius",
        "mi_blur_max_radius_bionics",
        "shade_blur_max_radius",
        "combined_blur_max_radius",
        "modal_glass_blur_max_radius",
        "notification_glass_small_blur_max_radius",
        "notification_glass_big_blur_max_radius",
        "notification_glass_small_blur_max_radius_heads_up_extra"
    )

    /** Dim scrim color behind the blurred surfaces; its alpha scales like the blend colors. */
    private const val DIM_COLOR = "notification_control_center_blur_dim_color"

    /**
     * Solid fallback background used when blur is unavailable or in solid mode; a flat grey
     * (`#ff6e6e6e`) that also reads as "the grey backdrop". Lightness and alpha follow the
     * blend sliders so it can be lightened or faded like the tint layers.
     */
    private const val SOLID_BG_COLOR = "notification_control_center_solid_background_color"

    /**
     * Card-level blend shade colors applied with `setMiBackgroundBlendColors` on top of the
     * notification / media card glass (Super Island notifications, media, ordinary rows). The grey
     * first layer is fully opaque (`#ff999999` / `#ff818181`), which is a big part of why the
     * glass cards look dark; their alpha scales with the glass-opacity slider.
     */
    private val CARD_SHADE_COLORS = setOf(
        "focus_notification_element_blend_shade_color_1",
        "focus_notification_element_blend_shade_color_2",
        "focus_notification_element_blend_shade_color_3",
        "media_notification_element_blend_shade_color_1",
        "media_notification_element_blend_shade_color_2",
        "media_notification_element_blend_shade_color_3",
        "notification_element_blend_shade_color_1",
        "notification_element_blend_shade_color_2",
        "notification_element_blend_headsUp_color_1",
        "notification_element_blend_headsUp_color_2"
    )

    private enum class Kind { BLEND_ARRAY, RADIUS_DIMEN, DIM_COLOR, SOLID_BG_COLOR, CARD_SHADE_COLOR, OTHER }

    /**
     * Control-center blend arrays from the miui.systemui.plugin resources (the plugin keeps
     * its own res table, so the SystemUI names above do not cover it). Classic-mode control
     * center surfaces read these through the plugin context's Resources.
     */
    private val PLUGIN_BLEND_ARRAYS = setOf(
        "miui_expanded_bg_blend_colors",
        "miui_expanded_bg_blend_colors_cc",
        "cc_tile_default_blend_colors"
    )

    // Re-read by an accepted settings change (refreshSettings); the hooks stay installed for the
    // process generation and read these copies on every resource/View funnel.
    private var enabled by generationState { false }
    private var blendAlpha by generationState { 1f }
    private var blendLightness by generationState { 1f }
    private var radiusScale by generationState { 1f }
    private var glassOpacity by generationState { 1f }
    private var glassTone by generationState { 1f }

    private var setGlass: XposedInterface.Invoker<*, Method>? by generationState { null }
    private var shadeBlur: WeakReference<Any>? by generationState { null }
    private var blurUtils: WeakReference<Any>? by generationState { null }
    private val originalShaders = generationResource(WeakHashMap<View, FloatArray>()) {
        synchronized(it) { it.clear() }
    }

    override fun saveHotReloadState(): Any = synchronized(originalShaders) { HashMap(originalShaders) }

    override fun restoreHotReloadState(state: Any?) {
        val entries = state as? Map<*, *> ?: return
        synchronized(originalShaders) {
            entries.forEach { (view, params) ->
                if (view is View && params is FloatArray) originalShaders.putIfAbsent(view, params)
            }
        }
        HostAccess.onMain { replayShaders() }
    }

    override fun onPrepareHotReload() = HostAccess.onMain {
        if (enabled) {
            enabled = false
            refreshHostResources()
            replayShaders()
        }
    }

    fun refreshSettings() = HostAccess.onMain {
        val wasEnabled = enabled
        val resourcesChanged = wasEnabled != settingGlassTunerEnabled ||
            blendAlpha != settingGlassTunerBlendAlpha || blendLightness != settingGlassTunerBlendLightness ||
            radiusScale != settingGlassTunerRadiusScale || glassOpacity != settingGlassTunerGlassOpacity
        val shadersChanged = wasEnabled != settingGlassTunerEnabled ||
            glassOpacity != settingGlassTunerGlassOpacity || glassTone != settingGlassTunerGlassTone
        readSettings()
        if (wasEnabled || enabled) {
            if (resourcesChanged) refreshHostResources()
            if (shadersChanged) replayShaders()
        }
        DebugLog.i(TAG, "settings applied enabled=$enabled blendAlpha=$blendAlpha " +
            "radiusScale=$radiusScale glassOpacity=$glassOpacity glassTone=$glassTone")
    }

    private fun readSettings() {
        enabled = settingGlassTunerEnabled
        blendAlpha = settingGlassTunerBlendAlpha
        blendLightness = settingGlassTunerBlendLightness
        radiusScale = settingGlassTunerRadiusScale
        glassOpacity = settingGlassTunerGlassOpacity
        glassTone = settingGlassTunerGlassTone
    }

    override fun onHook() {
        readSettings()
        // Collect baselines even while disabled so the first enable updates already-created views.
        SHADE_BLUR.toClassOrNull().orSkip("GlassMaterialHooker", SHADE_BLUR)?.hookAllConstructors {
            after { param -> shadeBlur = WeakReference(param.thisObject) }
        }
        BLUR_UTILS.toClassOrNull().orSkip("GlassMaterialHooker", BLUR_UTILS)?.hookAllConstructors {
            after { param -> blurUtils = WeakReference(param.thisObject) }
        }
        installFunnels()
    }

    private fun refreshHostResources() {
        runCatching {
            blurUtils?.get()?.let { HostAccess.invoke(it, "updateResources\$14") }
        }.onFailure { DebugLog.w(TAG, "Blur resource refresh failed", it) }
        val shade = shadeBlur?.get() ?: return
        runCatching {
            HostAccess.invoke(shade, "updateBlendColors")
            HostAccess.invoke(shade, "updateDimColor")
        }.onFailure { DebugLog.w(TAG, "Shade blend refresh failed", it) }
        runCatching {
            // The host's own callback reads the current heads-up state and reapplies the radii.
            val listener = HostAccess.read(shade, "configListener") ?: return@runCatching
            val boolean = Boolean::class.javaPrimitiveType!!
            HostAccess.method(listener, "onDensityOrFontScaleChanged", boolean, boolean)
                ?.invoke(listener, false, false)
        }.onFailure { DebugLog.w(TAG, "Shade radius refresh failed", it) }
    }

    private fun replayShaders() {
        val original = setGlass ?: return
        val entries = synchronized(originalShaders) { HashMap(originalShaders) }
        entries.forEach { (view, params) ->
            runCatching {
                val current = if (enabled) scaleGlassParams(params, glassOpacity, glassTone) else params
                original.invoke(view, current)
            }
                .onFailure { DebugLog.w(TAG, "Glass shader refresh failed", it) }
        }
    }

    private fun installFunnels() {
        val resources = Resources::class.java
        val targets = listOf(
            HostAccess.requirePublicMethod(resources, "getDimensionPixelSize", Int::class.javaPrimitiveType, accessible = false),
            HostAccess.requirePublicMethod(resources, "getIntArray", Int::class.javaPrimitiveType, accessible = false),
            HostAccess.requirePublicMethod(resources, "getColor", Int::class.javaPrimitiveType, Resources.Theme::class.java, accessible = false)
        )
        targets.forEach { method ->
            runCatching {
                deoptimize(method)
                method.hook {
                    after { param -> overrideResourceResult(param) }
                }
            }.onFailure {
                DebugLog.hookFailed(TAG, "Resources#${method.name}", it)
            }
        }

        hookViewSetMiGlass()
    }

    /**
     * Intercepts `View.setMiGlass(float[])` — the single native funnel every glass renderer
     * reaches, in the SystemUI APK (`MiGlassCompat.setMiGlassCompat` reflection) and in the
     * miui.systemui.plugin (control center / volume / brightness panels keep their glass tokens
     * as hardcoded constants, so only the View call can be intercepted). Hooking the View method
     * instead of each wrapper avoids double-scaling. All-zero arrays (the clear path) are left
     * untouched.
     */
    private fun hookViewSetMiGlass() {
        val method = runCatching {
            HostAccess.requireDeclaredMethod(View::class.java, "setMiGlass", FloatArray::class.java, accessible = true)
        }.getOrNull() ?: return DebugLog.hookSkipped(TAG, "View#setMiGlass", "method not found")

        runCatching {
            deoptimize(method)
            method.hook {
                intercept { chain ->
                    val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val params = chain.getArgs().getOrNull(0) as? FloatArray
                    val scaled = if (enabled && params != null)
                        scaleGlassParams(params, glassOpacity, glassTone) else params
                    val result = if (scaled === params) chain.proceed() else chain.proceed(arrayOf(scaled))
                    synchronized(originalShaders) {
                        if (params == null || params.all { it == 0f }) {
                            originalShaders.remove(view)
                        } else if (originalShaders[view]?.contentEquals(params) != true) {
                            originalShaders[view] = params.copyOf()
                        }
                    }
                    result
                }
            }
            setGlass = module.getInvoker(method).setType(XposedInterface.Invoker.Type.ORIGIN)
            DebugLog.hookRegistered(TAG, "View#setMiGlass")
        }.onFailure {
            DebugLog.hookFailed(TAG, "View#setMiGlass", it)
        }
    }

    private fun overrideResourceResult(param: HookParam) {
        if (!enabled) return
        val resources = param.thisObject as? Resources ?: return
        val id = (param.args.getOrNull(0) as? Int) ?: return
        val kind = kindOf(resources, id) ?: return
        val result = param.result
        param.result = when (kind) {
            Kind.BLEND_ARRAY -> (result as? IntArray)?.let {
                scaleBlendArrayColors(it, blendAlpha, blendLightness)
            }
            Kind.RADIUS_DIMEN -> scaleRadius(result as? Int ?: 0, radiusScale)
            Kind.DIM_COLOR -> {
                val color = result as? Int ?: 0
                scaleColorLightness(scaleColorAlpha(color, blendAlpha), blendLightness)
            }
            Kind.SOLID_BG_COLOR -> {
                val color = result as? Int ?: 0
                scaleColorLightness(scaleColorAlpha(color, blendAlpha), blendLightness)
            }
            Kind.CARD_SHADE_COLOR -> scaleColorAlpha(result as? Int ?: 0, glassOpacity)
            Kind.OTHER -> result
        }
    }

    // Plugin and SystemUI resource tables can reuse numeric IDs. Cache by asset table.
    private val kinds = generationResource(WeakHashMap<AssetManager, MutableMap<Int, Kind>>()) {
        synchronized(it) { it.clear() }
    }

    private fun kindOf(resources: Resources, id: Int): Kind? {
        if (id == 0) return null
        val kind = synchronized(kinds) {
            kinds.getOrPut(resources.assets) { HashMap() }.getOrPut(id) {
                when (runCatching { resources.getResourceEntryName(id) }.getOrNull()) {
                    in BLEND_ARRAYS, in PLUGIN_BLEND_ARRAYS -> Kind.BLEND_ARRAY
                    in RADIUS_DIMENS -> Kind.RADIUS_DIMEN
                    DIM_COLOR -> Kind.DIM_COLOR
                    SOLID_BG_COLOR -> Kind.SOLID_BG_COLOR
                    in CARD_SHADE_COLORS -> Kind.CARD_SHADE_COLOR
                    else -> Kind.OTHER
                }
            }
        }
        return kind.takeUnless { it == Kind.OTHER }
    }
}
