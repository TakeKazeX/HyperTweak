package com.takekazex.hypertweak.hook.rules.systemui.glass

import android.content.res.Resources
import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import java.util.concurrent.ConcurrentHashMap

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
 * opacity (lower = cards less tinted and less highlighted). Values are snapshotted at
 * hook-install time, so changes require a SystemUI restart; the GlassTunerPage offers one.
 */
object GlassMaterialHooker : StaticHooker() {
    private const val TAG = "GlassMaterial"

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
        "notification_glass_big_blur_max_radius"
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
     * notification / media card glass (focus notifications, media, ordinary rows). The grey
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
        "notification_element_blend_shade_color_2"
    )

    private enum class Kind { BLEND_ARRAY, RADIUS_DIMEN, DIM_COLOR, SOLID_BG_COLOR, CARD_SHADE_COLOR }

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

    // Snapshotted at hook-install time; a SystemUI restart applies new values.
    @Volatile
    private var enabled = false
    @Volatile
    private var blendAlpha = 1f
    @Volatile
    private var blendLightness = 1f
    @Volatile
    private var radiusScale = 1f
    @Volatile
    private var glassOpacity = 1f
    @Volatile
    private var glassTone = 1f

    // Resource identity -> kind, filled lazily from the first enabled read. The key is
    // "<package>#<id>", not the bare id: the SystemUI APK and the miui.systemui.plugin APK both
    // allocate ids in the 0x7f application range, so the same numeric id means different
    // resources on different Resources instances and caching by id alone would cross-contaminate
    // the two packages. Each identity costs exactly one package+name lookup.
    private val knownKinds = ConcurrentHashMap<String, Kind>()
    private val missedKeys = ConcurrentHashMap.newKeySet<String>()

    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    override fun onPrepareHotReload() {
        enabled = false
        knownIds.clear()
        missedIds.clear()
    }

    override fun onHook() {
        enabled = Preferences.getBoolean(Preferences.KEY_GLASS_TUNER_ENABLED, false)
        blendAlpha = Preferences.getFloat(Preferences.KEY_GLASS_TUNER_BLEND_ALPHA, 1f)
        blendLightness = Preferences.getFloat(Preferences.KEY_GLASS_TUNER_BLEND_LIGHTNESS, 1f)
        radiusScale = Preferences.getFloat(Preferences.KEY_GLASS_TUNER_RADIUS_SCALE, 1f)
        glassOpacity = Preferences.getFloat(Preferences.KEY_GLASS_TUNER_GLASS_OPACITY, 1f)
        glassTone = Preferences.getFloat(Preferences.KEY_GLASS_TUNER_GLASS_TONE, 1f)
        if (!enabled) {
            DebugLog.hookSkipped(TAG, "Resources#get* / View#setMiGlass", "glass tuner disabled")
            return
        }
        DebugLog.i(
            TAG,
            "glass tuner enabled, blendAlpha=$blendAlpha blendLightness=$blendLightness " +
                "radiusScale=$radiusScale glassOpacity=$glassOpacity glassTone=$glassTone"
        )

        val resources = Resources::class.java
        val targets = listOf(
            resources.getMethod("getDimensionPixelSize", Int::class.javaPrimitiveType),
            resources.getMethod("getIntArray", Int::class.javaPrimitiveType),
            resources.getMethod("getColor", Int::class.javaPrimitiveType),
            resources.getMethod("getColor", Int::class.javaPrimitiveType, Resources.Theme::class.java)
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
            View::class.java.getDeclaredMethod("setMiGlass", FloatArray::class.java).apply {
                isAccessible = true
            }
        }.getOrNull() ?: return DebugLog.hookSkipped(TAG, "View#setMiGlass", "method not found")

        runCatching {
            deoptimize(method)
            method.hook {
                intercept { chain ->
                    val params = chain.getArgs().getOrNull(0) as? FloatArray
                    if (params == null) {
                        return@intercept chain.proceed()
                    }
                    val scaled = scaleGlassParams(params, glassOpacity, glassTone)
                    if (scaled === params) {
                        chain.proceed()
                    } else {
                        chain.proceed(arrayOf(scaled))
                    }
                }
            }
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
        }
    }

    /**
     * Resolves and caches the kind for [id]. The hook sits on very hot `Resources` methods
     * (every dimension/color/array read in SystemUI), so the fast path must be a pair of plain
     * id lookups with no framework calls and no string allocation. Full package+name resolution
     * happens exactly once per resource id; ids collide between the SystemUI APK and the
     * miui.systemui.plugin APK (both allocate in the 0x7f range), but the plugin loads later,
     * so SystemUI's own ids are almost always resolved first — a rare wrong hit only scales one
     * unrelated array, never crashes.
     */
    private val knownIds = ConcurrentHashMap<Int, Kind>()
    private val missedIds = ConcurrentHashMap.newKeySet<Int>()

    private fun kindOf(resources: Resources, id: Int): Kind? {
        if (id == 0) return null
        knownIds[id]?.let { return it }
        if (id in missedIds) return null
        val name = runCatching { resources.getResourceEntryName(id) }.getOrNull()
        val kind = name?.let {
            when {
                it in BLEND_ARRAYS || it in PLUGIN_BLEND_ARRAYS -> Kind.BLEND_ARRAY
                it in RADIUS_DIMENS -> Kind.RADIUS_DIMEN
                it == DIM_COLOR -> Kind.DIM_COLOR
                it == SOLID_BG_COLOR -> Kind.SOLID_BG_COLOR
                it in CARD_SHADE_COLORS -> Kind.CARD_SHADE_COLOR
                else -> null
            }
        }
        if (kind != null) knownIds[id] = kind else missedIds += id
        return kind
    }
}
