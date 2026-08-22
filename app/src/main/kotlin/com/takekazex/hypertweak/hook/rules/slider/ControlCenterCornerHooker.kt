package com.takekazex.hypertweak.hook.rules.slider

import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
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
 * - [KEY_CC_CORNER_SLIDER] — the brightness/volume toggle slider track: mutates the
 *   `progressBg`/`progress`/`bionicsProgressBg` `GradientDrawable`s inside `ToggleSliderViewHolder`
 *   after `updateResources()`/`updateSize()` rebuild them and re-sets progress/outline radius.
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
 * plugin APK path exactly like [SliderPercentageHooker]. 0 dp means "no override" — the system
 * design wins. Requires a SystemUI restart (the plugin is rebuilt per restart) — read through
 * [Preferences] at hook time, so a restart-reload of the module while SystemUI stays up picks up
 * new values on the next plugin reload.
 */
class ControlCenterCornerHooker(
    private val pluginContext: android.content.Context? = null,
    private val pluginApkPath: String = ""
) : DynamicHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    @Volatile
    var cornerEnabled: Boolean = false
        private set

    private val resolvedPluginClasses by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (pluginContext == null || pluginApkPath.isEmpty()) emptyMap() else DexKitManager.resolveClasses(
            cacheDir = pluginContext.cacheDir,
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

    private val radiusMethodCache = ConcurrentHashMap<String, Method?>()
    private val sliderRadiusMethodCache = ConcurrentHashMap<String, Pair<Method?, Method?>>()
    private val iconFieldCache = ConcurrentHashMap<String, java.lang.reflect.Field?>()
    private val blurOutlineMethodCache = ConcurrentHashMap<String, Method?>()

    /** `setCornerRadius(float)` on ExpandableView/QS views/media holder. */
    private fun getSetCornerRadius(clazz: Class<*>): Method? =
        radiusMethodCache.getOrPut("setCornerRadius@${clazz.name}") {
            runCatching { clazz.getMethod("setCornerRadius", Float::class.javaPrimitiveType) }.getOrNull()
        }

    /** `setProgressRadius(float)` + `setOutlineRadius(float)` on ToggleSliderViewHolder. */
    private fun getSetSliderRadius(clazz: Class<*>): Pair<Method?, Method?> {
        val key = "sliderRadius@${clazz.name}"
        return sliderRadiusMethodCache.getOrPut(key) {
            val progress = runCatching { clazz.getMethod("setProgressRadius", Float::class.javaPrimitiveType) }.getOrNull()
            val outline = runCatching { clazz.getMethod("setOutlineRadius", Float::class.javaPrimitiveType) }.getOrNull()
            progress to outline
        }
    }

    /** Finds one declared method by name, optionally pinning the parameter count. */
    private fun findDeclaredMethod(clazz: Class<*>, name: String, paramCount: Int? = null): Method? =
        clazz.declaredMethods.firstOrNull { it.name == name && (paramCount == null || it.parameterCount == paramCount) }

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

    /** Full corner application: GradientDrawables + blur round-rect + outline invalidation. */
    private fun applyViewCorner(view: View, radius: Float) {
        setDrawableCorner(view, radius)
        applyBlurOutline(view, radius)
        view.invalidateOutline()
    }

    // ─── Reading prefs ────────────────────────────────────────────────────────────

    /**
     * dp value from prefs, converted to the px the plugin's radius APIs expect
     * (`GradientDrawable.setCornerRadius`, `setProgressRadius`, tile/card/media
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

    override fun onHook() {
        cornerEnabled = Preferences.getBoolean(Preferences.KEY_CC_CORNER_ENABLED, false)

        val sliderRadius = if (cornerEnabled) radiusPx(Preferences.KEY_CC_CORNER_SLIDER) else 0f
        val tileRadius = if (cornerEnabled) radiusPx(Preferences.KEY_CC_CORNER_TILE) else 0f
        val cardRadius = if (cornerEnabled) radiusPx(Preferences.KEY_CC_CORNER_CARD) else 0f
        val deviceRadius = if (cornerEnabled) radiusPx(Preferences.KEY_CC_CORNER_DEVICE) else 0f
        val mediaRadius = if (cornerEnabled) radiusPx(Preferences.KEY_CC_CORNER_MEDIA) else 0f

        Log.i("HyperTweak", "ControlCenterCorner: enabled=$cornerEnabled slider=$sliderRadius tile=$tileRadius card=$cardRadius device=$deviceRadius media=$mediaRadius")

        // ─── 1. Brightness/volume toggle slider track (ToggleSliderViewHolder) ─────────────
        if (sliderRadius > 0f) {
            hookToggleSlider(sliderRadius)
        }

        // ─── 2. Small QS tiles (QSTileItemIconView) ───────────────────────────────────────
        if (tileRadius > 0f) {
            hookQSTileIcon(tileRadius)
        }

        // ─── 3. Big cards / WiFi traffic card (QSCardItemView) ────────────────────────────
        if (cardRadius > 0f) {
            hookQSCard(cardRadius)
        }

        // ─── 3b. Device center (融合设备中心) entry row ─────────────────────────────────────
        if (deviceRadius > 0f) {
            hookDeviceCenter(deviceRadius)
        }

        // ─── 4. Media player (MediaPlayerController$MediaPlayerViewHolder) ────────────────
        if (mediaRadius > 0f) {
            hookMediaPlayer(mediaRadius)
        }
    }

    // ─── 1. ToggleSliderViewHolder ───────────────────────────────────────────────────────

    private fun hookToggleSlider(radius: Float) {
        val clz = resolveClass("miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder") ?: return

        val updateResources = findDeclaredMethod(clz, "updateResources", 0)
        updateResources?.let { method ->
            method.hook {
                after { param ->
                    runCatching {
                        applyToggleSliderCorner(param.thisObject, clz, radius)
                    }.onFailure { t -> Log.e("HyperTweak", "CC corner: toggle slider updateResources", t) }
                }
            }
        }

        val updateSize = findDeclaredMethod(clz, "updateSize", 0)
        updateSize?.let { method ->
            method.hook {
                after { param ->
                    runCatching {
                        applyToggleSliderCorner(param.thisObject, clz, radius)
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
                        applyToggleSliderCorner(param.thisObject, clz, radius)
                    }.onFailure { t -> Log.e("HyperTweak", "CC corner: toggle slider setDisableState", t) }
                }
            }
        }
    }

    private fun applyToggleSliderCorner(holder: Any, clz: Class<*>, radius: Float) {
        val binding = runCatching { clz.getMethod("getBinding").invoke(holder) }.getOrNull() ?: return
        // binding.progressBg / binding.progress / binding.bionicsProgressBg are public fields of
        // the ViewBinding; their backgrounds are GradientDrawables rebuilt by updateResources().
        for (fieldName in listOf("progressBg", "progress", "bionicsProgressBg")) {
            val view = runCatching { binding.javaClass.getField(fieldName).get(binding) as? View }.getOrNull()
                ?: runCatching { binding.javaClass.getMethod("get$fieldName").invoke(binding) as? View }.getOrNull()
            if (view != null) setDrawableCorner(view, radius)
        }
        // Keep the outline providers (they read the drawable corner radius / progressRadius
        // fields at invalidate time) consistent with the forced drawable.
        val setRadius = getSetSliderRadius(clz)
        setRadius.first?.invoke(holder, radius)
        setRadius.second?.invoke(holder, radius)
        val inner = runCatching { binding.javaClass.getField("toggleSliderInner").get(binding) as? View }.getOrNull()
            ?: runCatching { binding.javaClass.getMethod("getToggleSliderInner").invoke(binding) as? View }.getOrNull()
        inner?.invalidateOutline()
        val progress = runCatching { binding.javaClass.getField("progress").get(binding) as? View }.getOrNull()
            ?: runCatching { binding.javaClass.getMethod("getProgress").invoke(binding) as? View }.getOrNull()
        progress?.invalidateOutline()
    }

    // ─── 2. QSTileItemIconView ───────────────────────────────────────────────────────────

    private fun hookQSTileIcon(radius: Float) {
        val clz = resolveClass("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView") ?: return
        val setCorner = getSetCornerRadius(clz) ?: return
        setCorner.hook {
            intercept { chain ->
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
            runCatching { setCorner.invoke(holder, radius) }
                .onFailure { t -> Log.e("HyperTweak", "CC corner: tile corner", t) }
            val iconView = getTileIconView(holder, clz)
            if (iconView != null) {
                applyBlurOutline(iconView, radius)
                iconView.invalidateOutline()
            }
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

    private fun hookQSCard(radius: Float) {
        val clz = resolveClass("miui.systemui.controlcenter.qs.tileview.QSCardItemView") ?: return
        val setCorner = getSetCornerRadius(clz) ?: return
        setCorner.hook {
            intercept { chain ->
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
            runCatching { setCorner.invoke(holder, radius) }
                .onFailure { t -> Log.e("HyperTweak", "CC corner: card corner", t) }
            (holder as? View)?.let { view ->
                applyBlurOutline(view, radius)
                view.invalidateOutline()
            }
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

    private fun hookDeviceCenter(radius: Float) {
        // The entry row's rounded background (`external_entry_background`) is applied by layout
        // inflation and re-applied by the ViewHolder's onConfigurationChanged, so both points
        // re-run the same corner application (GradientDrawable corners + blur round-rect).
        val entryApplier: (View) -> Unit = { view ->
            runCatching { applyViewCorner(view, radius) }
                .onFailure { t -> Log.e("HyperTweak", "CC corner: device entry", t) }
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

    private fun hookMediaPlayer(radius: Float) {
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
                    runCatching { setCornerMethod.invoke(param.thisObject, radius) }
                        .onFailure { t -> Log.e("HyperTweak", "CC corner: media updateRadius", t) }
                }
            }
        }
    }
}