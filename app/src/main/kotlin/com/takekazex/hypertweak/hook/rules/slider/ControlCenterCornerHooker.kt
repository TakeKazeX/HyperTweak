package com.takekazex.hypertweak.hook.rules.slider

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.hook.base.dex.DexRuntime

import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HotReloadMode
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Control Center corner-radius overrides (控制中心圆角).
 *
 * The collapsed control center renders brightness/volume sliders, QS tiles, big cards, the media
 * player and the device-center (融合设备中心) row as rounded surfaces whose corner radius comes from
 * the plugin's dimens (`control_center_universal_corner_radius` 24dp, `toggle_slider_clip_round_corner_radius`
 * 2dp, ...). This hooker re-applies a user radius on the code points that rebuild those shapes:
 *
 * - [KEY_CC_CORNER_SLIDER] — the brightness/volume toggle slider TRACK: mutates the
 *   `progressBg`/`bionicsProgressBg` `GradientDrawable`s inside `ToggleSliderViewHolder` after
 *   `updateResources()`/`updateSize()` rebuild them and re-sets the track outline radius. The white
 *   progress fill is deliberately left at the vendor's own radius — see
 *   [applyToggleSliderCorner] for why forcing it there rounds the fill too.
 * - [KEY_CC_CORNER_TILE] — small QS tiles (`QSTileItemIconView`, the bottom quick-action grid):
 *   forces `setCornerRadius(float)` and, because the tiles switch between a GradientDrawable
 *   background and the bionics/glass blend surface (`applyTileBackgroundStyle` on the inner `icon`
 *   view), also drives the blur round-rect outline through `MiBlurCompat.setBlurOutlineRoundRect`
 *   so both render modes round to the requested value. Re-applied after every drawable/background
 *   rebuild (`setEnabledBg`/`setDisabledBg`, `updateSize`, `updateResources`, `liteIconUpdate`,
 *   `applyTileBackgroundStyle`).
 * - [KEY_CC_CORNER_CARD] — big cards / WiFi traffic card (`QSCardItemView`, the top WiFi/蜂窝
 *   cards): forces `setCornerRadius(float)` (which also re-writes the private `_cornerRadius`
 *   consumed by the card's own outline provider) and drives the blur round-rect outline. With the
 *   background-material (玻璃) theme ON, `updateBlurBlendBackground` clears the drawable
 *   background, installs the `_cornerRadius`-driven outline and paints via `MiBackgroundStyle`
 *   tokens — a path that never calls `setCornerRadius` — so corner overrides are re-applied after
 *   `updateSize`/`updateResources`/`updateBackground` instead.
 * - [KEY_CC_CORNER_DEVICE] — the device center (融合设备中心) entry row
 *   (`DeviceCenterEntryFrameLayout`, background `external_entry_background`):
 *   re-applies the corner on the view's GradientDrawable background plus the blur outline after
 *   `onFinishInflate` and after the entry ViewHolder's `onConfigurationChanged` re-sets the
 *   background resource.
 * - [KEY_CC_CORNER_MEDIA] — the media player (`MediaPlayerController$MediaPlayerViewHolder`):
 *   forces `setCornerRadius(float)` after `updateRadius()` computes it from the root background.
 *
 * All classes live in the `miui.systemui.plugin` APK, so resolution goes through DexKit with the
 * plugin APK path exactly like [SliderPercentageHooker].
 *
 * **No override is not zero**: while the master switch is off, or an individual value is 0 dp, the
 * hooks pass the host's own request through untouched. Only when a radius is active may a value be
 * substituted. Every view the module changed is tracked, and leaving the override restores the
 * host's original drawable corner/background and outline request, so turning the feature off does
 * not leave square corners on the host's rounded surfaces.
 */
class ControlCenterCornerHooker(
    private val pluginContext: android.content.Context? = null,
    private val pluginApkPath: String = ""
) : DynamicHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    private companion object {
        const val TILE_CLASS = "miui.systemui.controlcenter.qs.tileview.QSTileItemIconView"
        const val CARD_CLASS = "miui.systemui.controlcenter.qs.tileview.QSCardItemView"
        const val DEVICE_ENTRY_CLASS =
            "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryFrameLayout"
        const val SLIDER_CLASS = "miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder"
        const val MEDIA_CLASS = "miui.systemui.controlcenter.panel.main.media.MediaPlayerController"
    }

    @Volatile
    var cornerEnabled: Boolean = false
        private set

    private val resolvedPluginClasses by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (pluginContext == null || pluginApkPath.isEmpty()) emptyMap() else DexRuntime.resolveClasses(
            cacheDir = dexCacheDirectory,
            apkPath = pluginApkPath,
            classLoader = classLoader,
            queries = pluginClassQueries()
        )
    }

    private fun resolveClass(className: String): Class<Any>? {
        className.toClassOrNull()?.let { return it }
        val key = pluginClassKey(className) ?: return null
        @Suppress("UNCHECKED_CAST")
        return resolvedPluginClasses[key] as? Class<Any>
    }

    private fun pluginClassQueries() = mapOf<String, (org.luckypray.dexkit.DexKitBridge) -> String?>(
        "ToggleSliderViewHolder" to { bridge ->
            bridge.findClass {
                searchPackages("miui.systemui.controlcenter")
                matcher { className("ToggleSliderViewHolder", StringMatchType.EndsWith) }
            }.singleOrNull()?.name
        },
        "QSTileItemIconView" to { bridge ->
            bridge.findClass {
                searchPackages("miui.systemui.controlcenter")
                matcher { className("QSTileItemIconView", StringMatchType.EndsWith) }
            }.singleOrNull()?.name
        },
        "QSCardItemView" to { bridge ->
            bridge.findClass {
                searchPackages("miui.systemui.controlcenter")
                matcher { className("QSCardItemView", StringMatchType.EndsWith) }
            }.singleOrNull()?.name
        },
        "MediaPlayerController" to { bridge ->
            bridge.findClass {
                searchPackages("miui.systemui.controlcenter")
                matcher { className("MediaPlayerController", StringMatchType.EndsWith) }
            }.singleOrNull()?.name
        },
        "DeviceCenterEntryFrameLayout" to { bridge ->
            bridge.findClass {
                searchPackages("miui.systemui.controlcenter")
                matcher { className("DeviceCenterEntryFrameLayout", StringMatchType.EndsWith) }
            }.singleOrNull()?.name
        },
        "DeviceCenterEntryViewHolder" to { bridge ->
            bridge.findClass {
                searchPackages("miui.systemui.controlcenter")
                matcher { className("DeviceCenterEntryViewHolder", StringMatchType.EndsWith) }
            }.singleOrNull()?.name
        }
    )

    private fun pluginClassKey(className: String): String? = when (className) {
        "miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder" -> "ToggleSliderViewHolder"
        "miui.systemui.controlcenter.qs.tileview.QSTileItemIconView" -> "QSTileItemIconView"
        "miui.systemui.controlcenter.qs.tileview.QSCardItemView" -> "QSCardItemView"
        "miui.systemui.controlcenter.panel.main.media.MediaPlayerController" -> "MediaPlayerController"
        "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryFrameLayout" -> "DeviceCenterEntryFrameLayout"
        "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryViewHolder" -> "DeviceCenterEntryViewHolder"
        else -> null
    }

    override fun onPrepareHotReload() {
        radiusMethodCache.clear()
        sliderRadiusMethodCache.clear()
        iconFieldCache.clear()
        blurOutlineMethodCache.clear()
    }

    // ─── Cached reflective accessors (keyed by Class, the plugin reloads can reuse them) ─────

    /**
     * Host values the module replaced, so disabling can put them back. Weak keys: the plugin owns
     * the lifetime of every surface it creates.
     */
    private val overriddenCorners = java.util.WeakHashMap<View, CornerOverride>()

    /** Each slider holder's own outline radius before the module replaced it. */
    private val outlineBaselines = java.util.WeakHashMap<Any, Float>()

    private typealias CornerOverride = com.takekazex.hypertweak.hook.rules.systemui.CornerOverrideRecord

    private val radiusMethodCache = ConcurrentHashMap<String, Method?>()
    private val sliderRadiusMethodCache = ConcurrentHashMap<String, Method?>()
    private val iconFieldCache = ConcurrentHashMap<String, java.lang.reflect.Field?>()
    private val blurOutlineMethodCache = ConcurrentHashMap<String, Method?>()

    /** `setCornerRadius(float)` on ExpandableView/QS views/media holder. */
    private fun getSetCornerRadius(clazz: Class<*>): Method? =
        radiusMethodCache.getOrPut("setCornerRadius@${clazz.name}") {
            runCatching { clazz.getMethod("setCornerRadius", Float::class.javaPrimitiveType) }.getOrNull()
        }

    /**
     * `setOutlineRadius(float)` on ToggleSliderViewHolder (the track container's radius).
     * `setProgressRadius` is intentionally not resolved: the fill's own radius must keep the vendor
     * value, as [applyToggleSliderCorner] explains.
     */
    private fun getSetOutlineRadius(clazz: Class<*>): Method? =
        sliderRadiusMethodCache.getOrPut("setOutlineRadius@${clazz.name}") {
            runCatching { clazz.getMethod("setOutlineRadius", Float::class.javaPrimitiveType) }.getOrNull()
        }

    /** Finds one declared method by name, optionally pinning the parameter count. */
    private fun findDeclaredMethod(clazz: Class<*>, name: String, paramCount: Int? = null): Method? =
        clazz.declaredMethods.firstOrNull { it.name == name && (paramCount == null || it.parameterCount == paramCount) }

    /** Reads one public field (or its getter) off a plugin binding object. */
    private fun bindingView(binding: Any, fieldName: String): View? =
        runCatching { binding.javaClass.getField(fieldName).get(binding) as? View }.getOrNull()
            ?: runCatching { binding.javaClass.getMethod("get" + fieldName.replaceFirstChar { it.uppercase() })
                .invoke(binding) as? View }.getOrNull()

    /** Mirrors the inner `icon` ImageView of a `QSTileItemIconView` (the glass/icon surface). */
    private fun getTileIconView(holder: Any, clazz: Class<*>): View? {
        val field = iconFieldCache.getOrPut("icon@${clazz.name}") {
            runCatching { clazz.getDeclaredField("icon").apply { isAccessible = true } }.getOrNull()
        } ?: return null
        return runCatching { field.get(holder) as? View }.getOrNull()
    }

    /**
     * `MiBlurCompat.setBlurOutlineRoundRect(View, float)` from the plugin class loader, cached.
     * This is the API the platform uses to round blur-glass surfaces (the media player panel calls
     * it from its own `setCornerRadius`), and it is the only mechanism that reaches the
     * bionics/glass background material used by cards and tiles on OS4.
     */
    private fun getBlurOutlineRoundRect(): Method? {
        val key = "blurOutline@${classLoader.toString()}"
        return blurOutlineMethodCache.getOrPut(key) {
            runCatching {
                val miBlurCompat = classLoader.loadClass("miui.systemui.util.MiBlurCompat")
                miBlurCompat.getMethod("setBlurOutlineRoundRect", View::class.java, Float::class.javaPrimitiveType)
            }.getOrNull()
        }
    }

    /**
     * One corner override point. The host's own drawable/outline is recorded the first time the
     * module changes the surface; a non-positive radius means "no override" and restores it.
     */
    private fun overrideRadius(view: View, radius: Float) {
        if (!com.takekazex.hypertweak.hook.rules.systemui.CornerOverridePolicy.applies(true, radius)) {
            releaseRadius(view)
            return
        }
        val record = overriddenCorners.getOrPut(view) {
            CornerOverride(hostRadius(view), view.background, view.outlineProvider)
        }
        onHostRebuild(view, record)
        setDrawableCorner(view, radius)
        applyBlurOutline(view, radius)
        view.invalidateOutline()
    }

    /**
     * The host rebuilt this surface's background or outline. Drop the recorded copy (it describes
     * a drawable the host replaced) and re-capture the host's current value.
     */
    private fun onHostRebuild(view: View, record: CornerOverride) {
        if (record.background !== view.background) record.background = view.background
    }

    /**
     * Restores the host's own background and outline for one tracked surface and forgets it, so a
     * disabled feature stops being applied and the native rounding reappears immediately.
     */
    private fun releaseRadius(view: View) {
        val record = overriddenCorners.remove(view) ?: return
        runCatching {
            record.background?.let { view.background = it }
            record.outlineProvider?.let { view.outlineProvider = it }
            applyBlurOutline(view, record.hostRadius)
            view.invalidateOutline()
        }.onFailure { Log.e("HyperTweak", "CC corner: restore " + view.javaClass.simpleName, it) }
    }

    /** The corner radius the view's own background currently carries (0 for a non-round rect). */
    private fun hostRadius(view: View): Float {
        val bg = view.background ?: return 0f
        return when (bg) {
            is GradientDrawable -> runCatching { bg.cornerRadius }.getOrDefault(0f)
            is android.graphics.drawable.LayerDrawable ->
                (0 until bg.numberOfLayers)
                    .mapNotNull { index ->
                        runCatching { (bg.getDrawable(index)?.mutate() as? GradientDrawable)?.cornerRadius }.getOrNull()
                    }.firstOrNull() ?: 0f
            else -> 0f
        }
    }

    /** Applies the corner to the view's drawn GradientDrawable background/layers. */
    private fun setDrawableCorner(view: View, radius: Float) {
        val bg = view.background ?: return
        // mutate() before writing: `Resources.getDrawable` returns a shared cached instance for
        // the same id+theme, so mutating it in place would bleed the radius into every other view
        // using the same drawable (both brightness and volume use toggle_slider_background /
        // toggle_slider_progress_background; every device-center entry shares
        // external_entry_background). A per-instance copy keeps the override local.
        val mutated = bg.mutate()
        val gradient = mutated as? GradientDrawable
        if (gradient != null) {
            gradient.setCornerRadius(radius)
        } else if (mutated is android.graphics.drawable.LayerDrawable) {
            for (i in 0 until mutated.numberOfLayers) {
                val layer = mutated.getDrawable(i)?.mutate() as? GradientDrawable ?: continue
                layer.setCornerRadius(radius)
            }
        }
    }

    /** Rounds the blur-glass surface of [view] in addition to any drawable corners. */
    private fun applyBlurOutline(view: View, radius: Float) {
        val method = getBlurOutlineRoundRect() ?: return
        runCatching { method.invoke(null, view, radius) }
            .onFailure { t -> Log.e("HyperTweak", "CC corner: blur outline ${view.javaClass.simpleName}", t) }
    }

    // ─── Reading prefs ────────────────────────────────────────────────────────────

    /**
     * dp value from prefs, converted to the px the plugin's radius APIs expect
     * (`GradientDrawable.setCornerRadius`, `setOutlineRadius`, tile/card/media
     * `setCornerRadius` all take px). The plugin reads its dimens with
     * `getDimensionPixelSize`, so a raw dp passed through unchanged renders ~1/density
     * of the intended radius on high-density devices — the "no visible effect" report.
     */
    private fun radiusPx(key: String): Float {
        val dp = Preferences.getFloat(key, 0f)
        if (dp <= 0f) return 0f
        val density = pluginContext?.resources?.displayMetrics?.density ?: 1f
        return dp * density
    }

    /**
     * Accepted-snapshot refresh. The corner hooks are installed for the plugin session's whole
     * life and read [cornerEnabled] plus the five dp values at render time, so a first enable or
     * a coefficient change no longer needs a new plugin session or a SystemUI restart.
     */
    fun refreshSettings() {
        val next = Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_CORNER_ENABLED, false)
        val changed = next != cornerEnabled
        cornerEnabled = next
        if (!changed) {
            reapplyExisting()
            return
        }
        if (!next) restoreExisting() else reapplyExisting()
        Log.i("HyperTweak", "CC corner: applied enabled=" + next + " surfaces=" + overriddenCorners.size)
    }

    /** Re-applies the committed radius to every surface the module already changed. */
    private fun reapplyExisting() {
        if (!cornerEnabled) return
        overriddenCorners.keys.toList().filter { it.isAttachedToWindow }.forEach { view ->
            runCatching { overrideRadius(view, liveRadius(radiusKeyFor(view))) }
        }
    }

    /** Restores every recorded host value; used when the feature is switched off. */
    private fun restoreExisting() {
        overriddenCorners.keys.toList().forEach(::releaseRadius)
    }

    /** Tracks which preference owns an already-overridden surface, by its class family. */
    private fun radiusKeyFor(view: View): String = when {
        // The inner `icon` view of a tile is a plain ImageView; walk up to the tile holder.
        ancestors(view).any { it.javaClass.name == TILE_CLASS } -> SystemUiSettingsKeys.KEY_CC_CORNER_TILE
        ancestors(view).any { it.javaClass.name == CARD_CLASS } -> SystemUiSettingsKeys.KEY_CC_CORNER_CARD
        ancestors(view).any { it.javaClass.name == DEVICE_ENTRY_CLASS } -> SystemUiSettingsKeys.KEY_CC_CORNER_DEVICE
        else -> SystemUiSettingsKeys.KEY_CC_CORNER_SLIDER
    }

    /** The view itself and up to a handful of its ancestors, for a class-family lookup. */
    private fun ancestors(view: View): List<View> = buildList {
        var node: View? = view
        repeat(6) {
            val current = node ?: return@repeat
            add(current)
            node = current.parent as? View
        }
    }

    override fun onHook() {
        cornerEnabled = Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_CORNER_ENABLED, false)

        // Every hook reads the radius through this resolver at the moment it draws, so the
        // installed set never depends on the values that were committed at install time.
        val sliderRadius = liveRadius(SystemUiSettingsKeys.KEY_CC_CORNER_SLIDER)
        val tileRadius = liveRadius(SystemUiSettingsKeys.KEY_CC_CORNER_TILE)
        val cardRadius = liveRadius(SystemUiSettingsKeys.KEY_CC_CORNER_CARD)
        val deviceRadius = liveRadius(SystemUiSettingsKeys.KEY_CC_CORNER_DEVICE)
        val mediaRadius = liveRadius(SystemUiSettingsKeys.KEY_CC_CORNER_MEDIA)

        Log.i("HyperTweak", "ControlCenterCorner: enabled=$cornerEnabled slider=$sliderRadius tile=$tileRadius card=$cardRadius device=$deviceRadius media=$mediaRadius")

        // ─── 1. Brightness/volume toggle slider track (ToggleSliderViewHolder) ─────────────
        hookToggleSlider(SystemUiSettingsKeys.KEY_CC_CORNER_SLIDER)

        // ─── 2. Small QS tiles (QSTileItemIconView) ───────────────────────────────────────
        hookQSTileIcon(SystemUiSettingsKeys.KEY_CC_CORNER_TILE)

        // ─── 3. Big cards / WiFi traffic card (QSCardItemView) ────────────────────────────
        hookQSCard(SystemUiSettingsKeys.KEY_CC_CORNER_CARD)

        // ─── 3b. Device center (融合设备中心) entry row ─────────────────────────────────────
        hookDeviceCenter(SystemUiSettingsKeys.KEY_CC_CORNER_DEVICE)

        // ─── 4. Media player (MediaPlayerController$MediaPlayerViewHolder) ────────────────
        hookMediaPlayer(SystemUiSettingsKeys.KEY_CC_CORNER_MEDIA)
    }

    /**
     * The radius a hook may substitute right now. Zero means "no override": the host's own
     * request must be passed through, never replaced by a square corner.
     */
    private fun liveRadius(key: String): Float =
        if (com.takekazex.hypertweak.hook.rules.systemui.CornerOverridePolicy.applies(cornerEnabled, radiusPx(key)))
            radiusPx(key) else 0f

    // ─── 1. ToggleSliderViewHolder ───────────────────────────────────────────────────────

    private fun hookToggleSlider(radiusKey: String) {
        // Read at draw time: a committed radius applies to the living plugin session.
        val clz = resolveClass("miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder") ?: return

        val updateResources = findDeclaredMethod(clz, "updateResources", 0)
        updateResources?.let { method ->
            method.hook {
                after { param ->
                    runCatching {
                        applyToggleSliderCorner(param.thisObject, clz, liveRadius(radiusKey))
                    }.onFailure { t -> Log.e("HyperTweak", "CC corner: toggle slider updateResources", t) }
                }
            }
        }

        val updateSize = findDeclaredMethod(clz, "updateSize", 0)
        updateSize?.let { method ->
            method.hook {
                after { param ->
                    runCatching {
                        applyToggleSliderCorner(param.thisObject, clz, liveRadius(radiusKey))
                    }.onFailure { t -> Log.e("HyperTweak", "CC corner: toggle slider updateSize", t) }
                }
            }
        }

        // setDisableState swaps the progress drawable for the disabled variant; re-apply.
        val setDisableState = findDeclaredMethod(clz, "setDisableState")
        setDisableState?.let { method ->
            method.hook {
                after { param ->
                    runCatching {
                        applyToggleSliderCorner(param.thisObject, clz, liveRadius(radiusKey))
                    }.onFailure { t -> Log.e("HyperTweak", "CC corner: toggle slider setDisableState", t) }
                }
            }
        }
    }

    private fun applyToggleSliderCorner(holder: Any, clz: Class<*>, radius: Float) {
        val binding = runCatching { clz.getMethod("getBinding").invoke(holder) }.getOrNull() ?: return
        // This override is the slider TRACK radius only. The vendor gives the track and the white
        // progress fill deliberately different stock radii and derives the fill's rounding from both:
        //   track (progressBg / bionicsProgressBg, @attr/sliderBgColor)
        //         = control_center_universal_corner_radius (24dp)
        //   fill  (progress, @attr/sliderFgColor)
        //         = toggle_slider_clip_round_corner_radius (2dp)
        // `ToggleSliderViewHolder$3` is the fill's ViewOutlineProvider (installed on `progress`); it
        // sizes its round rect to the filled fraction and takes its radius from
        // `ApiUtils.i(getProgressRadius(), progressBg.background.cornerRadius)` — `i` is `min()` — so
        // raising progressBg's drawable corner also raises the fill's ceiling. Applying the override
        // to `progressBg`/`bionicsProgressBg` plus setOutlineRadius below is therefore the entire
        // override; `progress` and setProgressRadius stay untouched so the fill keeps its own 2dp.
        // Both track drawables are rebuilt by updateResources(), hence the after-hooks.
        if (radius <= 0f) {
            for (fieldName in listOf("progressBg", "bionicsProgressBg")) {
                val view = runCatching { binding.javaClass.getField(fieldName).get(binding) as? View }.getOrNull()
                    ?: runCatching { binding.javaClass.getMethod("get$fieldName").invoke(binding) as? View }.getOrNull()
                if (view != null) releaseRadius(view)
            }
            getSetOutlineRadius(clz)?.let { setter ->
                outlineBaselines[holder]?.let { baseline ->
                    outlineBaselines.remove(holder)
                    runCatching { setter.invoke(holder, baseline) }
                }
            }
            return
        }
        for (fieldName in listOf("progressBg", "bionicsProgressBg")) {
            val view = runCatching { binding.javaClass.getField(fieldName).get(binding) as? View }.getOrNull()
                ?: runCatching { binding.javaClass.getMethod("get$fieldName").invoke(binding) as? View }.getOrNull()
            if (view != null) overrideRadius(view, radius)
        }
        // Only the track container's outline radius (`toggleSliderInner`, provider `$2`, which falls
        // back to `outlineRadius` when progressBg's background is not a GradientDrawable) is forced.
        // setProgressRadius is deliberately NOT called: the fill's own radius must stay at the vendor
        // value, because `$3` computes min(progressRadius, progressBg.cornerRadius) and forcing both
        // to the user radius is exactly what rounded the white progress.
        val outlineSetter = getSetOutlineRadius(clz)
        if (outlineSetter != null) {
            if (!outlineBaselines.containsKey(holder)) {
                // The host's own outline radius before the module replaced it: the track drawable
                // the vendor just rebuilt is the value the host would have used.
                val trackView = bindingView(binding, "progressBg")
                outlineBaselines[holder] = trackView?.let(::hostRadius) ?: 0f
            }
            runCatching { outlineSetter.invoke(holder, radius) }
        }
        val inner = runCatching { binding.javaClass.getField("toggleSliderInner").get(binding) as? View }.getOrNull()
            ?: runCatching { binding.javaClass.getMethod("getToggleSliderInner").invoke(binding) as? View }.getOrNull()
        inner?.invalidateOutline()
        // The fill's outline reads progressBg's drawable corner, so it must be recomputed now that
        // the track corner changed; it stays at min(vendor 2dp, track radius) as a result.
        val progress = runCatching { binding.javaClass.getField("progress").get(binding) as? View }.getOrNull()
            ?: runCatching { binding.javaClass.getMethod("getProgress").invoke(binding) as? View }.getOrNull()
        progress?.invalidateOutline()
    }

    // ─── 2. QSTileItemIconView ───────────────────────────────────────────────────────────

    private fun hookQSTileIcon(radiusKey: String) {
        // Read at draw time: a committed radius applies to the living plugin session.
        val clz = resolveClass("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView") ?: return
        val setCorner = getSetCornerRadius(clz) ?: return
        setCorner.hook {
            intercept { chain ->
                val radius = liveRadius(radiusKey)
                if (radius <= 0f) return@intercept chain.proceed()
                (chain.thisObject as? View)?.let { view -> overrideRadius(view, radius) }
                chain.proceed(chain.args.toTypedArray().also { args ->
                    if (args.isNotEmpty() && args[0] is Float) args[0] = radius
                })
            }
        }
        // The tile's visible corner is driven by the enabledBg/disabledBg GradientDrawables that
        // updateIconInternal() re-creates on every state change (via setEnabledBg/setDisabledBg)
        // — that path never calls setCornerRadius, so re-apply our radius right after the new
        // drawables are assigned. With the bionics/glass background material the blend surface
        // lives on the inner `icon` view (`applyTileBackgroundStyle`), which needs the blur
        // round-rect outline instead. Both paths must be covered; updateSize, updateResources and
        // liteIconUpdate re-run for config changes and the complete-control-center=false fallback.
        val tileApplier: (Any) -> Unit = { holder ->
            val radius = liveRadius(radiusKey)
            runCatching {
                if (radius <= 0f) {
                    // No override: hand the host's own value back, so a tile that was rounded by
                    // this module returns to the vendor radius on the next rebuild.
                    (holder as? View)?.let(::releaseRadius)
                    getTileIconView(holder, clz)?.let(::releaseRadius)
                } else {
                    (holder as? View)?.let { view -> overrideRadius(view, radius) }
                    getTileIconView(holder, clz)?.let { iconView ->
                        overrideRadius(iconView, radius)
                        iconView.invalidateOutline()
                    }
                }
            }.onFailure { t -> Log.e("HyperTweak", "CC corner: tile corner", t) }
        }
        for (methodName in listOf("setEnabledBg", "setDisabledBg")) {
            findDeclaredMethod(clz, methodName, 1)?.let { m ->
                m.hook {
                    after { param ->
                        runCatching { tileApplier(param.thisObject) }
                            .onFailure { t -> Log.e("HyperTweak", "CC corner: tile $methodName", t) }
                    }
                }
            }
        }
        for ((methodName, paramCount) in listOf("updateSize" to 0, "updateResources" to 0, "liteIconUpdate" to 2, "applyTileBackgroundStyle" to 2)) {
            findDeclaredMethod(clz, methodName, paramCount)?.let { m ->
                m.hook {
                    after { param ->
                        runCatching { tileApplier(param.thisObject) }
                            .onFailure { t -> Log.e("HyperTweak", "CC corner: tile $methodName", t) }
                    }
                }
            }
        }
    }

    // ─── 3. QSCardItemView ───────────────────────────────────────────────────────────────

    private fun hookQSCard(radiusKey: String) {
        // Read at draw time: a committed radius applies to the living plugin session.
        val clz = resolveClass("miui.systemui.controlcenter.qs.tileview.QSCardItemView") ?: return
        val setCorner = getSetCornerRadius(clz) ?: return
        setCorner.hook {
            intercept { chain ->
                val radius = liveRadius(radiusKey)
                if (radius <= 0f) return@intercept chain.proceed()
                (chain.thisObject as? View)?.let { view -> overrideRadius(view, radius) }
                chain.proceed(chain.args.toTypedArray().also { args ->
                    if (args.isNotEmpty() && args[0] is Float) args[0] = radius
                })
            }
        }
        // `setCornerRadius` re-writes the private `_cornerRadius` consumed by the card's outline
        // provider. With the background material (glass) theme, `updateBlurBlendBackground`
        // clears the drawable background and repaints through MiBackgroundStyle tokens — that
        // path never calls setCornerRadius, so the after-hooks below re-apply the override over
        // the blur round-rect outline after every size/resource/background rebuild.
        val cardApplier: (Any) -> Unit = { holder ->
            val radius = liveRadius(radiusKey)
            runCatching {
                if (radius <= 0f) (holder as? View)?.let(::releaseRadius)
                else (holder as? View)?.let { view -> overrideRadius(view, radius) }
            }.onFailure { t -> Log.e("HyperTweak", "CC corner: card corner", t) }
        }
        for ((methodName, paramCount) in listOf("updateSize" to 0, "updateResources" to 1, "updateBackground" to 2)) {
            findDeclaredMethod(clz, methodName, paramCount)?.let { m ->
                m.hook {
                    after { param ->
                        runCatching { cardApplier(param.thisObject) }
                            .onFailure { t -> Log.e("HyperTweak", "CC corner: card $methodName", t) }
                    }
                }
            }
        }
    }

    // ─── 3b. DeviceCenterEntryFrameLayout / DeviceCenterEntryViewHolder ──────────────────

    private fun hookDeviceCenter(radiusKey: String) {
        // Read at draw time: a committed radius applies to the living plugin session.
        // The entry row's rounded background (`external_entry_background`) is applied by layout
        // inflation and re-applied by the ViewHolder's onConfigurationChanged, so both points
        // re-run the same corner application (GradientDrawable corners + blur round-rect).
        val entryApplier: (View) -> Unit = { view ->
            val radius = liveRadius(radiusKey)
            runCatching {
                if (radius <= 0f) releaseRadius(view) else overrideRadius(view, radius)
            }.onFailure { t -> Log.e("HyperTweak", "CC corner: device entry", t) }
        }
        val frameClz = resolveClass("miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryFrameLayout") ?: return
        findDeclaredMethod(frameClz, "onFinishInflate", 0)?.let { method ->
            method.hook {
                after { param ->
                    runCatching {
                        (param.thisObject as? View)?.let(entryApplier)
                    }.onFailure { t -> Log.e("HyperTweak", "CC corner: device entry onFinishInflate", t) }
                }
            }
        }
        val holderClz = resolveClass("miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryViewHolder")
        if (holderClz != null) {
            findDeclaredMethod(holderClz, "onConfigurationChanged", 1)?.let { method ->
                method.hook {
                    after { param ->
                        runCatching {
                            val holder = param.thisObject
                            val itemView = runCatching { holder.javaClass.getMethod("getItemView").invoke(holder) as? View }
                                .getOrNull() ?: runCatching { holder.javaClass.getField("itemView").get(holder) as? View }.getOrNull()
                            if (itemView != null) entryApplier(itemView)
                        }.onFailure { t -> Log.e("HyperTweak", "CC corner: device entry onConfigurationChanged", t) }
                    }
                }
            }
        }
    }

    // ─── 4. MediaPlayerController ─────────────────────────────────────────────────────────

    private fun hookMediaPlayer(radiusKey: String) {
        // Read at draw time: a committed radius applies to the living plugin session.
        val clz = resolveClass("miui.systemui.controlcenter.panel.main.media.MediaPlayerController") ?: return
        // The ViewHolder inner class carries setCornerRadius(float) (MediaFromView override).
        val holderClz = runCatching {
            clz.declaredClasses.firstOrNull { it.name.endsWith("MediaPlayerViewHolder") }
        }.getOrNull() ?: runCatching {
            classLoader.loadClass("${clz.name}\$MediaPlayerViewHolder")
        }.getOrNull() ?: return
        val setCorner = getSetCornerRadius(holderClz)
        val setCornerMethod = setCorner ?: runCatching {
            holderClz.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
        }.getOrNull() ?: return
        setCornerMethod.hook {
            intercept { chain ->
                val radius = liveRadius(radiusKey)
                if (radius <= 0f) return@intercept chain.proceed()
                (chain.thisObject as? View)?.let { view -> overrideRadius(view, radius) }
                chain.proceed(chain.args.toTypedArray().also { args ->
                    if (args.isNotEmpty() && args[0] is Float) args[0] = radius
                })
            }
        }
        // updateRadius() reads the radius back from the root background drawable; after the
        // hook above forces it, a later updateResources() re-sets the background and
        // updateRadius() recomputes from the drawable corner — force it again there.
        val updateRadius = findDeclaredMethod(holderClz, "updateRadius", 0)
        updateRadius?.let { method ->
            method.hook {
                after { param ->
                    val radius = liveRadius(radiusKey)
                    runCatching {
                        if (radius <= 0f) (param.thisObject as? View)?.let(::releaseRadius)
                        else setCornerMethod.invoke(param.thisObject, radius)
                    }.onFailure { t -> Log.e("HyperTweak", "CC corner: media updateRadius", t) }
                }
            }
        }
    }
}