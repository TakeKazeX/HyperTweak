package com.takekazex.hypertweak.hook.rules.slider

import com.takekazex.hypertweak.hook.base.HostAccess
import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.takekazex.hypertweak.util.platform.ResourceLookup
import java.util.WeakHashMap

// ─── Package-Level View Extensions ─────────────────────────────────────────

private object MiBlurMethodCache {
    fun getMethod(view: View, name: String, vararg paramTypes: Class<*>): java.lang.reflect.Method? =
        HostAccess.publicMethod(view.javaClass, name, *paramTypes, accessible = false)
}

fun View.setMiViewBlurMode(mode: Int) {
    runCatching {
        MiBlurMethodCache.getMethod(this, "setMiViewBlurMode", Int::class.javaPrimitiveType!!)?.invoke(this, mode)
    }
}

fun View.clearMiBackgroundBlendColor() {
    runCatching {
        MiBlurMethodCache.getMethod(this, "clearMiBackgroundBlendColor")?.invoke(this)
    }
}

fun View.setMiBackgroundBlurRadius(radius: Int) {
    runCatching {
        MiBlurMethodCache.getMethod(this, "setMiBackgroundBlurRadius", Int::class.javaPrimitiveType!!)?.invoke(this, radius)
    }
}

fun View.setMiBackgroundBlurMode(mode: Int) {
    runCatching {
        MiBlurMethodCache.getMethod(this, "setMiBackgroundBlurMode", Int::class.javaPrimitiveType!!)?.invoke(this, mode)
    }
}

fun View.chooseBackgroundBlurContainer(container: View?) {
    runCatching {
        MiBlurMethodCache.getMethod(this, "chooseBackgroundBlurContainer", View::class.java)?.invoke(this, container)
    }
}

fun View.setPassWindowBlurEnabled(enabled: Boolean) {
    runCatching {
        MiBlurMethodCache.getMethod(this, "setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType!!)?.invoke(this, enabled)
    }
}

fun View.clearMiBlur() {
    setMiViewBlurMode(0)
    setMiBackgroundBlurMode(0)
    setMiBackgroundBlurRadius(0)
    setPassWindowBlurEnabled(false)
    clearMiBackgroundBlendColor()
}

object ColorOverrideLock {
    val isSettingColor = ThreadLocal.withInitial { false }
}

inline fun withColorOverride(block: () -> Unit) {
    ColorOverrideLock.isSettingColor.set(true)
    try {
        block()
    } finally {
        ColorOverrideLock.isSettingColor.set(false)
    }
}

// ─── Shared Slider Hooker Helper ───────────────────────────────────────────

object SliderHookHelper {
    val tags = WeakHashMap<Any, HashMap<String, Any?>>()

    @Volatile
    var sameStyleEnabled = false

    @Synchronized
    fun getTag(obj: Any, key: String): Any? {
        return tags[obj]?.get(key)
    }

    @Synchronized
    fun putTag(obj: Any, key: String, value: Any?) {
        tags.getOrPut(obj) { HashMap() }[key] = value
    }

    @Synchronized
    fun removeTag(obj: Any, key: String): Any? {
        return tags[obj]?.remove(key)
    }

    /**
     * Live slider records, so a committed change reaches the sliders that are on screen now
     * instead of waiting for their next bind. Weak keys: the plugin owns the holders.
     */
    private val liveSliders = java.util.WeakHashMap<Any, SliderRecord>()

    private class SliderRecord(
        val controller: Any,
        val type: String,
        var topText: TextView?,
        /** The host's own label visibility/typeface before the module showed the percentage. */
        var originalVisibility: Int?,
        var originalTypeface: android.graphics.Typeface?
    )

    /** Remembers the slider a bind just touched; used by [applyCommittedState]. */
    fun trackSlider(sliderController: Any, type: String, topText: TextView) {
        val record = liveSliders[sliderController]
        if (record == null) {
            liveSliders[sliderController] = SliderRecord(sliderController, type, topText,
                topText.visibility, topText.typeface)
        } else {
            // The holder view can be recycled; the visibility/typeface baseline stays with the
            // controller, but the text view reference follows the current holder.
            record.topText = topText
            if (record.originalVisibility == null) record.originalVisibility = topText.visibility
            if (record.originalTypeface == null) record.originalTypeface = topText.typeface
        }
    }

    /**
     * Applies the committed percentage policy to every living slider: enabled renders the value
     * on the current holder, disabled restores the host's own visibility and weight. The caller
     * passes the resolved switch value so this file never reads preferences on the render path.
     */
    fun applyCommittedState(showPercentage: Boolean) {
        liveSliders.values.toList().forEach { record ->
            val view = record.topText ?: getTag(record.controller, "cached_topText") as? TextView
            if (view == null || !view.isAttachedToWindow) return@forEach
            if (showPercentage) {
                trackSlider(record.controller, record.type, view)
                initTopText(view)
                updatePercentageText(record.controller, record.type)
            } else {
                restoreTopText(record, view)
            }
        }
    }

    /** Puts one label back exactly as the host left it before the percentage was shown. */
    private fun restoreTopText(record: SliderRecord, view: TextView) {
        record.originalTypeface?.let { view.typeface = it }
        record.originalVisibility?.let { if (view.visibility != it) view.visibility = it }
        view.clearMiBlur()
        removeTag(record.controller, "cached_topText")
        removeTag(record.controller, "cached_slider")
    }

    /** One settings generation changed: controller caches are dropped, live holders re-applied. */
    fun onSettingsChanged() {
        tags.clear()
    }

    /** Drops the cached holder views for a controller so a recycled ViewHolder re-resolves them. */
    fun invalidateHolderViews(sliderController: Any) {
        removeTag(sliderController, "cached_topText")
        removeTag(sliderController, "cached_slider")
    }

    /**
     * Refreshes the per-controller view cache from a freshly bound holder. Called from
     * onBindViewHolder so a recycled ViewHolder points the cache at its current views instead of
     * stranding percentage updates on the previous, now-detached ones. Falls back to invalidation
     * when the slider cannot be resolved, so [updatePercentageText] re-resolves rather than caching
     * a half-populated pair.
     */
    fun refreshViewCache(sliderController: Any, holder: Any, topText: TextView) {
        val slider = resolveSliderFromHolder(holder)
        if (slider != null) {
            putTag(sliderController, "cached_topText", topText)
            putTag(sliderController, "cached_slider", slider)
        } else {
            invalidateHolderViews(sliderController)
        }
    }

    private fun resolveSliderFromHolder(holder: Any): SeekBar? =
        runCatching { HostAccess.requirePublicField(holder.javaClass, "slider", accessible = false).get(holder) as? SeekBar }.getOrNull()
            ?: runCatching { HostAccess.requirePublicMethod(holder.javaClass, "getSlider", accessible = false).invoke(holder) as? SeekBar }.getOrNull()

    fun blendColors(color1: Int, color2: Int, fraction: Float): Int {
        val a = (color1 ushr 24 and 0xff) + ((color2 ushr 24 and 0xff) - (color1 ushr 24 and 0xff)) * fraction
        val r = (color1 ushr 16 and 0xff) + ((color2 ushr 16 and 0xff) - (color1 ushr 16 and 0xff)) * fraction
        val g = (color1 ushr 8 and 0xff) + ((color2 ushr 8 and 0xff) - (color1 ushr 8 and 0xff)) * fraction
        val b = (color1 and 0xff) + ((color2 and 0xff) - (color1 and 0xff)) * fraction
        return (a.toInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    val ACTIVE_BLUE_COLOR = "#3482FF".toColorInt()
    private val DARK_TEXT_COLOR = "#B3FFFFFF".toColorInt()
    private val LIGHT_TEXT_COLOR = "#B3000000".toColorInt()

    fun formatPercent(percent: Int): String = "$percent%"

    fun getSliderTextColor(context: android.content.Context): Int {
        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (isDark) DARK_TEXT_COLOR else LIGHT_TEXT_COLOR
    }

    @Volatile
    private var isBlurSupportedMethod: java.lang.reflect.Method? = null
    @Volatile
    private var isBlurSupportedMethodLoaded = false

    fun isBlurSupported(context: android.content.Context): Boolean {
        val method = synchronized(this) {
            if (isBlurSupportedMethodLoaded) {
                isBlurSupportedMethod
            } else {
                val m = runCatching {
                    val clz = context.classLoader.loadClass("miui.systemui.controlcenter.utils.ControlCenterUtils")
                    HostAccess.requirePublicMethod(clz, "getBackgroundBlurOpenedInDefaultTheme", android.content.Context::class.java, accessible = false)
                }.getOrNull()
                isBlurSupportedMethod = m
                isBlurSupportedMethodLoaded = true
                m
            }
        }
        if (method != null) {
            return runCatching { method.invoke(null, context) as Boolean }.getOrDefault(false)
        }
        return false
    }

    private val activeColorCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun clearActiveColorCache() {
        activeColorCache.clear()
        cachedBlendColorsResId = -1
        cachedBlendColorsResources = null
        cachedOriginalBlendColors = null
    }

    @Synchronized
    fun clearHotReloadCaches() {
        tags.clear()
        sameStyleEnabled = false
        isBlurSupportedMethod = null
        isBlurSupportedMethodLoaded = false
        clearActiveColorCache()
        holderMethodCache.clear()
    }

    // Cached blendColors resource lookup (avoids getIdentifier + createPackageContext per call)
    @Volatile
    private var cachedBlendColorsResId: Int = -1  // -1 = not resolved, 0 = not found
    @Volatile
    private var cachedBlendColorsResources: android.content.res.Resources? = null
    @Volatile
    private var cachedOriginalBlendColors: IntArray? = null

    fun getActiveColor(context: android.content.Context, sliderType: String? = null): Int {
        val cacheKey = sliderType ?: "default"
        val cachedColor = activeColorCache[cacheKey]
        if (cachedColor != null) {
            return cachedColor
        }

        val color = runCatching {
            if (sliderType != null && sliderType.contains("Volume")) {
                val sysUiContext = ResourceLookup.packageContext(context, "com.android.systemui")
                val colorResId = sysUiContext?.let {
                    ResourceLookup.identifier(it, "miui_volume_icon_color_blue", "color", "com.android.systemui")
                } ?: 0
                if (colorResId != 0 && sysUiContext != null) {
                    return@runCatching sysUiContext.getColor(colorResId)
                }
                return@runCatching ACTIVE_BLUE_COLOR
            }
            
            val colorResId = ResourceLookup.identifier(context, "toggle_slider_active_color", "color", context.packageName)
            if (colorResId != 0) {
                val csl = context.resources.getColorStateList(colorResId, context.theme)
                val stateSets = listOf(
                    intArrayOf(android.R.attr.state_activated),
                    intArrayOf(android.R.attr.state_selected),
                    intArrayOf(android.R.attr.state_activated, android.R.attr.state_selected),
                    intArrayOf(android.R.attr.state_checked)
                )
                val hsv = FloatArray(3)
                var bestColor = csl.defaultColor
                var maxSaturation = -1f

                android.graphics.Color.colorToHSV(bestColor, hsv)
                if (hsv[1] > 0.15f) {
                    maxSaturation = hsv[1]
                }

                for (states in stateSets) {
                    val c = csl.getColorForState(states, csl.defaultColor)
                    android.graphics.Color.colorToHSV(c, hsv)
                    if (hsv[1] > maxSaturation) {
                        maxSaturation = hsv[1]
                        bestColor = c
                    }
                }
                if (maxSaturation >= 0.15f) {
                    return@runCatching bestColor
                }
            }
            android.graphics.Color.argb(0xFF, 0xFF, 0x98, 0x00)
        }.getOrDefault(android.graphics.Color.argb(0xFF, 0xFF, 0x98, 0x00))

        activeColorCache[cacheKey] = color
        return color
    }

    fun applyTopTextStyle(topText: TextView, force: Boolean = false, sliderType: String? = null) {
        val context = topText.context
        val blurSupported = isBlurSupported(context)
        val sameStyle = sameStyleEnabled
        val resolvedType = sliderType ?: getTag(topText, "sliderType") as? String

        if (resolvedType != null) {
            putTag(topText, "sliderType", resolvedType)
        }

        if (sameStyle) {
            val activeColor = getActiveColor(context, resolvedType)
            topText.isActivated = true
            topText.isSelected = true

            // Cached resource lookup — only resolves once
            val blendColorsResId: Int
            val blendColorsResources: android.content.res.Resources
            val cached = cachedBlendColorsResId
            if (cached >= 0) {
                blendColorsResId = cached
                blendColorsResources = cachedBlendColorsResources ?: context.resources
            } else {
                var resId = ResourceLookup.identifier(context, "toggle_slider_icon_blend_colors", "array", context.packageName)
                var resources = context.resources
                if (resId == 0) {
                    runCatching {
                        val pluginResources = ResourceLookup.packageResources(context, "miui.systemui.plugin")
                        val id = pluginResources?.let {
                            ResourceLookup.identifier(it, "toggle_slider_icon_blend_colors", "array", "miui.systemui.plugin")
                        } ?: 0
                        if (id != 0 && pluginResources != null) {
                            resId = id
                            resources = pluginResources
                        }
                    }
                }
                cachedBlendColorsResId = resId
                cachedBlendColorsResources = resources
                blendColorsResId = resId
                blendColorsResources = resources
            }

            if (blurSupported && blendColorsResId != 0) {
                withColorOverride {
                    runCatching {
                    topText.setTextColor(android.content.res.ColorStateList.valueOf(activeColor))
                }.onFailure { t ->
                    Log.e("HyperTweak", "applyTopTextStyle: color set failed", t)
                }
                }

                // Cached getMiViewBlurMode method
                val blurModeMethod = HostAccess.publicMethod(topText.javaClass, "getMiViewBlurMode", accessible = false)
                val currentMode = if (blurModeMethod != null) {
                    runCatching { blurModeMethod.invoke(topText) as? Int }.getOrNull() ?: 0
                } else 0

                if (currentMode != 3 || force) {
                    topText.clearMiBlur()
                    topText.setMiViewBlurMode(3)
                    runCatching {
                        // Cached original blend colors array
                        val originalBlendColors = cachedOriginalBlendColors ?: run {
                            val arr = blendColorsResources.getIntArray(blendColorsResId)
                            cachedOriginalBlendColors = arr
                            arr
                        }
                        var forcedBlendColors = getTag(topText, "cached_forcedBlendColors_$activeColor") as? IntArray
                        if (forcedBlendColors == null) {
                            val hsv = FloatArray(3)
                            forcedBlendColors = IntArray(originalBlendColors.size) { i ->
                                val c = originalBlendColors[i]
                                android.graphics.Color.colorToHSV(c, hsv)
                                if (hsv[1] > 0.3f) {
                                    val a = android.graphics.Color.alpha(c)
                                    val r = android.graphics.Color.red(activeColor)
                                    val g = android.graphics.Color.green(activeColor)
                                    val b = android.graphics.Color.blue(activeColor)
                                    android.graphics.Color.argb(a, r, g, b)
                                } else {
                                    c
                                }
                            }
                            putTag(topText, "cached_forcedBlendColors_$activeColor", forcedBlendColors)
                        }

                        // Cached setMiBackgroundBlendColors method (static, not per-view)
                        val blendMethod = runCatching {
                            val clz = context.classLoader.loadClass("miui.systemui.util.MiBlurCompat")
                            HostAccess.publicMethod(clz, "setMiBackgroundBlendColors", View::class.java,
                                IntArray::class.java, Float::class.javaPrimitiveType!!, accessible = false)
                        }.getOrNull()
                        blendMethod?.invoke(null, topText, forcedBlendColors, 1f)
                    }.onFailure { t ->
                        Log.e("HyperTweak", "applyTopTextStyle: failed to setMiBackgroundBlendColors", t)
                        topText.clearMiBlur()
                    }
                }
            } else {
                topText.clearMiBlur()
                withColorOverride {
                    runCatching {
                    topText.setTextColor(android.content.res.ColorStateList.valueOf(activeColor))
                }.onFailure { t ->
                    Log.e("HyperTweak", "applyTopTextStyle: color set failed", t)
                }
                }
            }
        } else {
            topText.clearMiBlur()
            runCatching {
                withColorOverride {
                    topText.setTextColor(getSliderTextColor(context))
                }
            }.onFailure { t ->
                Log.e("HyperTweak", "applyTopTextStyle: no-blur color set failed", t)
            }
        }
    }

    fun initTopText(topText: TextView) {
        topText.visibility = View.VISIBLE
        topText.typeface = Typeface.DEFAULT_BOLD
    }

    private val holderMethodCache = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Method?>()
    private val holderMethodNames = listOf("getSliderHolder", "getHolder", "getViewHolder", "getSliderViewHolder", "getItemViewHolder")

    fun findHolder(sliderController: Any): Any? {
        val clazz = sliderController.javaClass
        val cached = holderMethodCache[clazz]
        if (cached != null) {
            return runCatching { cached.invoke(sliderController) }.getOrNull()
        }
        for (name in holderMethodNames) {
            val r = runCatching {
                val m = HostAccess.requireDeclaredMethod(clazz, name, accessible = true)
                val result = m.invoke(sliderController)
                if (result != null) holderMethodCache[clazz] = m
                result
            }.recoverCatching {
                val m = HostAccess.requirePublicMethod(clazz, name, accessible = false)
                val result = m.invoke(sliderController)
                if (result != null) holderMethodCache[clazz] = m
                result
            }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    fun getTopTextFromHolder(holder: Any): TextView? = runCatching {
        HostAccess.publicMethod(holder.javaClass, "getTopText", accessible = false)?.invoke(holder) as? TextView
    }.getOrNull()

    fun calcVolumePercent(sliderController: Any): Int? = runCatching {
        val systemVolume = HostAccess.readDeclared(sliderController, "systemVolume") as? Int ?: return@runCatching null
        if (systemVolume == Int.MIN_VALUE) return@runCatching null
        val max = HostAccess.readDeclared(sliderController, "sliderMaxValue") as? Int ?: return@runCatching null
        val min = HostAccess.readDeclared(sliderController, "sliderMinValue") as? Int ?: return@runCatching null
        volumePercent(systemVolume * 1000, min, max)
    }.getOrNull()

    fun calcVolumePercentFromSliderValue(sliderController: Any, sliderValue: Int): Int? = runCatching {
        val method = HostAccess.declaredMethod(sliderController.javaClass, "valueToVolume", Int::class.javaPrimitiveType!!, accessible = true)
        val level = method?.invoke(sliderController, sliderValue) as? Int ?: return@runCatching null
        val max = HostAccess.readDeclared(sliderController, "streamMaxVolume") as? Int ?: return@runCatching null
        val min = HostAccess.readDeclared(sliderController, "streamMinVolume") as? Int ?: return@runCatching null
        volumePercent(level, min, max)
    }.getOrNull()

    fun calcBrightnessPercent(slider: SeekBar): Int? {
        val min = slider.min
        val max = slider.max
        val clazz = slider.javaClass
        // Resolve getTargetValue()/getValue() once per SeekBar class — this runs on every drag frame.
        val method = HostAccess.cached(clazz, "slider-brightness:value-getter", java.lang.reflect.Method::class.java) {
            HostAccess.publicMethod(clazz, "getTargetValue", accessible = false) ?: HostAccess.publicMethod(clazz, "getValue", accessible = false)
        }
        val value = method?.let { runCatching { it.invoke(slider) as? Int }.getOrNull() } ?: slider.progress
        // null when the range is degenerate or a spring overshoot leaves the value out of range —
        // the caller keeps the previous text instead of flashing "0%".
        return volumePercent(value, min, max)
    }

    fun updatePercentageText(sliderController: Any, type: String) {
        val result = runCatching {
            var topText = getTag(sliderController, "cached_topText") as? TextView
            var slider = getTag(sliderController, "cached_slider") as? SeekBar

            if (topText == null || slider == null) {
                val holder = findHolder(sliderController)
                    ?: throw NullPointerException("Could not find holder via any known method")

                topText = getTopTextFromHolder(holder)
                    ?: throw NullPointerException("topText view not found in holder")

                slider = resolveSliderFromHolder(holder)
                    ?: throw NullPointerException("slider view not found in holder")

                putTag(sliderController, "cached_topText", topText)
                putTag(sliderController, "cached_slider", slider)
            }

            val pct = if (type == "VolumeSliderController") {
                calcVolumePercent(sliderController)
            } else {
                calcBrightnessPercent(slider)
            } ?: return@runCatching

            setPercentIfChanged(topText, pct)
            applyTopTextStyle(topText, sliderType = type)
            trackSlider(sliderController, type, topText)
        }

        result.onFailure { t ->
            Log.e("HyperTweak", "Failed to update percentage text for $type", t)
        }
    }
}
