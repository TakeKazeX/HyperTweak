package com.takekazex.hypertweak.hook.rules.slider

import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.takekazex.hypertweak.util.ResourceLookup
import java.util.WeakHashMap

// ─── Package-Level View Extensions ─────────────────────────────────────────

private object MiBlurMethodCache {
    private val methodCache = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method?>()

    fun getMethod(view: View, name: String, vararg paramTypes: Class<*>): java.lang.reflect.Method? {
        val key = "${view.javaClass.name}#$name"
        return methodCache.getOrPut(key) {
            runCatching { view.javaClass.getMethod(name, *paramTypes) }.getOrNull()
        }
    }

    fun clear() {
        methodCache.clear()
    }
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

// ─── Shared Slider Hooker Helper ───────────────────────────────────────────

object SliderHookHelper {
    val tags = WeakHashMap<Any, HashMap<String, Any?>>()

    @Volatile
    var sameStyleEnabled = false

    // Animator cache coordinates
    var fromLeft = 0
    var fromTop = 0
    var fromWidth = 0
    var fromHeight = 0
    var toLeft = 0
    var toTop = 0
    var toWidth = 0
    var toHeight = 0

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
                    clz.getMethod("getBackgroundBlurOpenedInDefaultTheme", android.content.Context::class.java)
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
        MiBlurMethodCache.clear()
        tags.clear()
        sameStyleEnabled = false
        fromLeft = 0
        fromTop = 0
        fromWidth = 0
        fromHeight = 0
        toLeft = 0
        toTop = 0
        toWidth = 0
        toHeight = 0
        isBlurSupportedMethod = null
        isBlurSupportedMethodLoaded = false
        clearActiveColorCache()
        getMiViewBlurModeMethod = null
        getMiViewBlurModeMethodLoaded = false
        setBlendMethod = null
        setBlendMethodLoaded = false
        holderMethodCache.clear()
        topTextMethodCache.clear()
    }

    // Cached blendColors resource lookup (avoids getIdentifier + createPackageContext per call)
    @Volatile
    private var cachedBlendColorsResId: Int = -1  // -1 = not resolved, 0 = not found
    @Volatile
    private var cachedBlendColorsResources: android.content.res.Resources? = null
    @Volatile
    private var cachedOriginalBlendColors: IntArray? = null

    // Cached getMiViewBlurMode method (avoids getMethod per call)
    @Volatile
    private var getMiViewBlurModeMethod: java.lang.reflect.Method? = null
    @Volatile
    private var getMiViewBlurModeMethodLoaded = false

    // Cached setMiBackgroundBlendColors method (static, not per-view)
    @Volatile
    private var setBlendMethod: java.lang.reflect.Method? = null
    @Volatile
    private var setBlendMethodLoaded = false

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
                ColorOverrideLock.isSettingColor.set(true)
                runCatching {
                    topText.setTextColor(android.content.res.ColorStateList.valueOf(activeColor))
                }.onFailure { t ->
                    Log.e("HyperTweak", "applyTopTextStyle: color set failed", t)
                }
                ColorOverrideLock.isSettingColor.set(false)

                // Cached getMiViewBlurMode method
                val blurModeMethod = synchronized(this) {
                    if (getMiViewBlurModeMethodLoaded) {
                        getMiViewBlurModeMethod
                    } else {
                        val m = runCatching {
                            topText.javaClass.getMethod("getMiViewBlurMode")
                        }.getOrNull()
                        getMiViewBlurModeMethod = m
                        getMiViewBlurModeMethodLoaded = true
                        m
                    }
                }
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
                        val blendMethod = synchronized(this) {
                            if (setBlendMethodLoaded) {
                                setBlendMethod
                            } else {
                                val m = runCatching {
                                    val clz = context.classLoader.loadClass("miui.systemui.util.MiBlurCompat")
                                    clz.getMethod(
                                        "setMiBackgroundBlendColors",
                                        View::class.java,
                                        IntArray::class.java,
                                        Float::class.javaPrimitiveType
                                    )
                                }.getOrNull()
                                setBlendMethod = m
                                setBlendMethodLoaded = true
                                m
                            }
                        }
                        blendMethod?.invoke(null, topText, forcedBlendColors, 1f)
                    }.onFailure { t ->
                        Log.e("HyperTweak", "applyTopTextStyle: failed to setMiBackgroundBlendColors", t)
                        topText.clearMiBlur()
                    }
                }
            } else {
                topText.clearMiBlur()
                ColorOverrideLock.isSettingColor.set(true)
                runCatching {
                    topText.setTextColor(android.content.res.ColorStateList.valueOf(activeColor))
                }.onFailure { t ->
                    Log.e("HyperTweak", "applyTopTextStyle: color set failed", t)
                }
                ColorOverrideLock.isSettingColor.set(false)
            }
        } else {
            topText.clearMiBlur()
            runCatching {
                ColorOverrideLock.isSettingColor.set(true)
                topText.setTextColor(getSliderTextColor(context))
            }.onFailure { t ->
                Log.e("HyperTweak", "applyTopTextStyle: no-blur color set failed", t)
            }
            ColorOverrideLock.isSettingColor.set(false)
        }
    }

    fun initTopText(topText: TextView) {
        topText.visibility = View.VISIBLE
        topText.typeface = Typeface.DEFAULT_BOLD
        topText.post {
            if (topText.text == "200%") {
                topText.text = "0%"
            }
        }
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
                val m = clazz.getDeclaredMethod(name).also { it.isAccessible = true }
                val result = m.invoke(sliderController)
                if (result != null) holderMethodCache[clazz] = m
                result
            }.recoverCatching {
                val m = clazz.getMethod(name)
                val result = m.invoke(sliderController)
                if (result != null) holderMethodCache[clazz] = m
                result
            }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    private val topTextMethodCache = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Method?>()

    fun getTopTextFromHolder(holder: Any): TextView? {
        val clazz = holder.javaClass
        val cached = topTextMethodCache[clazz]
        if (cached != null) {
            return runCatching { cached.invoke(holder) as? TextView }.getOrNull()
        }
        return runCatching {
            val m = clazz.getMethod("getTopText")
            topTextMethodCache[clazz] = m
            m.invoke(holder) as? TextView
        }.getOrNull()
    }

    fun calcVolumePercent(sliderController: Any): Int {
        return runCatching {
            var fSystemVolume = getTag(sliderController, "cached_fSystemVolume") as? java.lang.reflect.Field
            var fSliderMaxValue = getTag(sliderController, "cached_fSliderMaxValue") as? java.lang.reflect.Field
            var fSliderMinValue = getTag(sliderController, "cached_fSliderMinValue") as? java.lang.reflect.Field

            if (fSystemVolume == null || fSliderMaxValue == null || fSliderMinValue == null) {
                fSystemVolume = sliderController.javaClass.getDeclaredField("systemVolume").apply { isAccessible = true }
                fSliderMaxValue = sliderController.javaClass.getDeclaredField("sliderMaxValue").apply { isAccessible = true }
                fSliderMinValue = sliderController.javaClass.getDeclaredField("sliderMinValue").apply { isAccessible = true }

                putTag(sliderController, "cached_fSystemVolume", fSystemVolume)
                putTag(sliderController, "cached_fSliderMaxValue", fSliderMaxValue)
                putTag(sliderController, "cached_fSliderMinValue", fSliderMinValue)
            }

            val systemVolume = fSystemVolume?.get(sliderController) as? Int ?: return@runCatching 0
            val sliderMaxValue = fSliderMaxValue?.get(sliderController) as? Int ?: return@runCatching 0
            val sliderMinValue = fSliderMinValue?.get(sliderController) as? Int ?: return@runCatching 0

            Math.round((systemVolume * 1000f - sliderMinValue) / (sliderMaxValue - sliderMinValue) * 100f).coerceIn(0, 100)
        }.getOrDefault(0)
    }

    fun calcVolumePercentFromSliderValue(sliderController: Any, sliderValue: Int): Int {
        return runCatching {
            var mValueToVolume = getTag(sliderController, "cached_mValueToVolume") as? java.lang.reflect.Method
            var fStreamMaxVolume = getTag(sliderController, "cached_fStreamMaxVolume") as? java.lang.reflect.Field
            var fStreamMinVolume = getTag(sliderController, "cached_fStreamMinVolume") as? java.lang.reflect.Field

            if (mValueToVolume == null || fStreamMaxVolume == null || fStreamMinVolume == null) {
                mValueToVolume = sliderController.javaClass.getDeclaredMethod("valueToVolume", Int::class.javaPrimitiveType).apply { isAccessible = true }
                fStreamMaxVolume = sliderController.javaClass.getDeclaredField("streamMaxVolume").apply { isAccessible = true }
                fStreamMinVolume = sliderController.javaClass.getDeclaredField("streamMinVolume").apply { isAccessible = true }

                putTag(sliderController, "cached_mValueToVolume", mValueToVolume)
                putTag(sliderController, "cached_fStreamMaxVolume", fStreamMaxVolume)
                putTag(sliderController, "cached_fStreamMinVolume", fStreamMinVolume)
            }

            val level = mValueToVolume?.invoke(sliderController, sliderValue) as? Int ?: return@runCatching 0
            val maxLevel = fStreamMaxVolume?.get(sliderController) as? Int ?: return@runCatching 0
            val minLevel = fStreamMinVolume?.get(sliderController) as? Int ?: return@runCatching 0

            if (maxLevel <= 0) 0
            else Math.round((level - minLevel).toFloat() / (maxLevel - minLevel) * 100f).coerceIn(0, 100)
        }.getOrDefault(0)
    }

    fun calcBrightnessPercent(slider: SeekBar): Int {
        val min = slider.min
        val max = slider.max
        val value = runCatching {
            slider.javaClass.getMethod("getTargetValue").invoke(slider) as? Int
        }.recoverCatching {
            slider.javaClass.getMethod("getValue").invoke(slider) as? Int
        }.getOrNull() ?: slider.progress
        return Math.round((value - min).toFloat() / (max - min) * 100f).coerceIn(0, 100)
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

                slider = runCatching { holder.javaClass.getField("slider").get(holder) as? SeekBar }.getOrNull()
                    ?: runCatching { holder.javaClass.getMethod("getSlider").invoke(holder) as? SeekBar }.getOrNull()
                    ?: throw NullPointerException("slider view not found in holder")

                putTag(sliderController, "cached_topText", topText)
                putTag(sliderController, "cached_slider", slider)
            }

            val pct = if (type == "VolumeSliderController") {
                calcVolumePercent(sliderController)
            } else {
                calcBrightnessPercent(slider)
            }

                topText.text = formatPercent(pct)
            applyTopTextStyle(topText, sliderType = type)
        }

        result.onFailure { t ->
            Log.e("HyperTweak", "Failed to update percentage text for $type", t)
        }
    }
}
