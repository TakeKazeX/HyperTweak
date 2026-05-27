package com.takekazex.hypertweak.hook.rules

import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import java.util.WeakHashMap

class SliderPercentageHooker : DynamicHooker() {

    private val tags = WeakHashMap<Any, HashMap<String, Any?>>()
    private var brightnessColor: Int = 0
    private var volumeColor: Int = 0
    private var brightnessBlendToken: Any? = null
    private var volumeBlendToken: Any? = null

    // Animator cache coordinates
    private var fromLeft = 0
    private var fromTop = 0
    private var fromWidth = 0
    private var fromHeight = 0
    private var toLeft = 0
    private var toTop = 0
    private var toWidth = 0
    private var toHeight = 0

    @Synchronized
    private fun getTag(obj: Any, key: String): Any? {
        return tags[obj]?.get(key)
    }

    @Synchronized
    private fun putTag(obj: Any, key: String, value: Any?) {
        tags.getOrPut(obj) { HashMap() }[key] = value
    }

    @Synchronized
    private fun removeTag(obj: Any, key: String): Any? {
        return tags[obj]?.remove(key)
    }

    private fun blendColors(color1: Int, color2: Int, fraction: Float): Int {
        val a = (color1 ushr 24 and 0xff) + ((color2 ushr 24 and 0xff) - (color1 ushr 24 and 0xff)) * fraction
        val r = (color1 ushr 16 and 0xff) + ((color2 ushr 16 and 0xff) - (color1 ushr 16 and 0xff)) * fraction
        val g = (color1 ushr 8 and 0xff) + ((color2 ushr 8 and 0xff) - (color1 ushr 8 and 0xff)) * fraction
        val b = (color1 and 0xff) + ((color2 and 0xff) - (color1 and 0xff)) * fraction
        return (a.toInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    private fun <T : View> View.findViewByIdNameAs(name: String): T? {
        val id = context.resources.getIdentifier(name, "id", context.packageName)
        if (id == 0) return null
        @Suppress("UNCHECKED_CAST")
        return findViewById<View>(id) as? T
    }

    private fun isBlurSupported(context: android.content.Context): Boolean {
        return runCatching {
            val clz = context.classLoader.loadClass("miui.systemui.controlcenter.utils.ControlCenterUtils")
            clz.getMethod("getBackgroundBlurOpenedInDefaultTheme", android.content.Context::class.java).invoke(null, context) as Boolean
        }.getOrDefault(false)
    }

    // View blur extension helpers using reflection
    private fun View.setMiViewBlurMode(mode: Int) {
        runCatching {
            this.javaClass.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType).invoke(this, mode)
        }
    }

    private fun View.clearMiBackgroundBlendColor() {
        runCatching {
            this.javaClass.getMethod("clearMiBackgroundBlendColor").invoke(this)
        }
    }

    private fun View.setMiBackgroundBlurRadius(radius: Int) {
        runCatching {
            this.javaClass.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType).invoke(this, radius)
        }
    }

    private fun View.setMiBackgroundBlurMode(mode: Int) {
        runCatching {
            this.javaClass.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType).invoke(this, mode)
        }
    }

    private fun View.chooseBackgroundBlurContainer(container: View?) {
        runCatching {
            this.javaClass.getMethod("chooseBackgroundBlurContainer", View::class.java).invoke(this, container)
        }
    }

    private fun View.setPassWindowBlurEnabled(enabled: Boolean) {
        runCatching {
            this.javaClass.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType).invoke(this, enabled)
        }
    }

    private fun View.clearMiBlur() {
        setMiViewBlurMode(0)
        setMiBackgroundBlurMode(0)
        setMiBackgroundBlurRadius(0)
        setPassWindowBlurEnabled(false)
        clearMiBackgroundBlendColor()
    }

    /**
     * Apply the correct color/blur style to a topText view.
     * Mirrors OShape's updateTopTextBlendColor().
     * - sameStyle=true + blur supported: setMiViewBlurMode(3) + blend colors from int-array resource
     * - otherwise: plain text color from toggle_slider_icon_color resource (or fallback gray)
     */
    private fun applyTopTextStyle(topText: TextView) {
        val context = topText.context
        val blurSupported = isBlurSupported(context)
        val sameStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)

        if (sameStyle && blurSupported) {
            // Check if already in blur mode (mode 3)
            val currentMode = runCatching {
                topText.javaClass.getMethod("getMiViewBlurMode").invoke(topText) as? Int
            }.getOrNull() ?: 0
            if (currentMode == 3) return

            // Apply blend color from resource array (same as OShape's toggle_slider_icon_blend_colors)
            topText.clearMiBlur()
            runCatching {
                val colorStateListResId = context.resources.getIdentifier(
                    "toggle_slider_icon_color", "color", context.packageName)
                if (colorStateListResId != 0) {
                    topText.setTextColor(context.resources.getColorStateList(colorStateListResId, context.theme))
                }
            }
            topText.setMiViewBlurMode(3)
            runCatching {
                val blendColorsResId = context.resources.getIdentifier(
                    "toggle_slider_icon_blend_colors", "array", context.packageName)
                if (blendColorsResId != 0) {
                    val blendColors = context.resources.getIntArray(blendColorsResId)
                    val clzMiBlurCompat = context.classLoader.loadClass("miui.systemui.util.MiBlurCompat")
                    clzMiBlurCompat.getMethod(
                        "setMiBackgroundBlendColors",
                        View::class.java,
                        IntArray::class.java,
                        Float::class.javaPrimitiveType
                    ).invoke(null, topText, blendColors, 1f)
                }
            }.onFailure { t ->
                Log.w("HyperTweak", "applyTopTextStyle: blend color failed", t)
            }
        } else {
            // Plain style: gray text (matches OShape's Color.argb(0xFF, 0x99, 0x99, 0x99))
            topText.clearMiBlur()
            runCatching {
                val colorResId = context.resources.getIdentifier(
                    "toggle_slider_top_text_color", "color", context.packageName)
                if (colorResId != 0) {
                    topText.setTextColor(context.resources.getColor(colorResId, context.theme))
                } else {
                    topText.setTextColor(android.graphics.Color.argb(0xFF, 0x99, 0x99, 0x99))
                }
            }.onFailure {
                topText.setTextColor(android.graphics.Color.argb(0xFF, 0x99, 0x99, 0x99))
            }
        }
    }

    /**
     * Initialize a topText view: make VISIBLE, set bold typeface, reset placeholder text.
     * Mirrors OShape's initTopText().
     */
    private fun initTopText(topText: TextView) {
        topText.post {
            topText.visibility = View.VISIBLE
            topText.typeface = Typeface.DEFAULT_BOLD
            // OShape resets the placeholder "200%" that the slider sets initially
            if (topText.text == "200%") {
                topText.text = "0%"
            }
        }
    }

    /**
     * getSliderHolder() on BSC/VSC is PRIVATE, so getMethod() won't find it.
     * The parent class MainPanelListItem.Controller exposes getHolder() publicly,
     * which is exactly what getSliderHolder() delegates to internally.
     */
    private fun findHolder(sliderController: Any): Any? {
        for (name in listOf("getSliderHolder", "getHolder", "getViewHolder", "getSliderViewHolder", "getItemViewHolder")) {
            val r = runCatching { 
                val m = sliderController.javaClass.getDeclaredMethod(name).also { it.isAccessible = true }
                m.invoke(sliderController) 
            }.recoverCatching {
                sliderController.javaClass.getMethod(name).invoke(sliderController)
            }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    private fun getTopTextFromHolder(holder: Any): TextView? {
        return runCatching { holder.javaClass.getMethod("getTopText").invoke(holder) as? TextView }.getOrNull()
    }

    private fun updatePercentageText(sliderController: Any, type: String) {
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

            topText.text = "$pct%"
        }

        result.onFailure { t ->
            Log.e("HyperTweak", "Failed to update percentage text for $type", t)
        }
    }

    /**
     * Calculate CC slider percentage for Volume (using systemVolume field).
     * OShape: systemVolume * 1000 / sliderMaxValue, clamped.
     */
    private fun calcVolumePercent(sliderController: Any): Int {
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

            val systemVolume = fSystemVolume!!.get(sliderController) as Int
            val sliderMaxValue = fSliderMaxValue!!.get(sliderController) as Int
            val sliderMinValue = fSliderMinValue!!.get(sliderController) as Int

            // OShape: (systemVolume * 1000 - sliderMinValue) / (sliderMaxValue - sliderMinValue) * 100
            Math.round((systemVolume * 1000f - sliderMinValue) / (sliderMaxValue - sliderMinValue) * 100f).coerceIn(0, 100)
        }.getOrDefault(0)
    }

    /**
     * Calculate CC slider percentage for Volume from a raw slider value (used in updateSliderValue hook).
     * Uses valueToVolume to convert slider int value to real volume level, then percent.
     */
    private fun calcVolumePercentFromSliderValue(sliderController: Any, sliderValue: Int): Int {
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

            val level = mValueToVolume!!.invoke(sliderController, sliderValue) as Int
            val maxLevel = fStreamMaxVolume!!.get(sliderController) as Int
            val minLevel = fStreamMinVolume!!.get(sliderController) as Int

            if (maxLevel <= 0) 0
            else Math.round((level - minLevel).toFloat() / (maxLevel - minLevel) * 100f).coerceIn(0, 100)
        }.getOrDefault(0)
    }

    /**
     * Calculate brightness percentage from SeekBar.
     * Uses getTargetValue() / getValue() to avoid stale progress.
     */
    private fun calcBrightnessPercent(slider: SeekBar): Int {
        val min = slider.min
        val max = slider.max
        val value = runCatching {
            slider.javaClass.getMethod("getTargetValue").invoke(slider) as? Int
        }.recoverCatching {
            slider.javaClass.getMethod("getValue").invoke(slider) as? Int
        }.getOrNull() ?: slider.progress
        return Math.round((value - min).toFloat() / (max - min) * 100f).coerceIn(0, 100)
    }

    override fun onHook() {
        Log.d("HyperTweak", "SliderPercentageHooker attaching hooks")

        // ─── Slider Controllers ────────────────────────────────────────────────
        val clzBrightnessSlider = "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController".toClassOrNull()
        val clzVolumeSlider = "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController".toClassOrNull()

        Log.d("HyperTweak", "clzBrightnessSlider: ${clzBrightnessSlider?.name}")
        Log.d("HyperTweak", "clzVolumeSlider: ${clzVolumeSlider?.name}")

        clzBrightnessSlider?.declaredMethods?.firstOrNull { it.name == "onBindViewHolder" }?.let { method ->
            Log.d("HyperTweak", "Hooking Brightness onBindViewHolder (for initTopText)")
            method.hook {
                val res = proceed()
                if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    runCatching {
                        val holder = findHolder(thisObject) ?: return@runCatching
                        val topText = getTopTextFromHolder(holder) ?: return@runCatching
                        initTopText(topText)

                        // Link AnimateColorView for coloring
                        val binding = runCatching { holder.javaClass.getMethod("getBinding").invoke(holder) }
                            .recoverCatching { holder.javaClass.getDeclaredField("binding").apply { isAccessible = true }.get(holder) }
                            .getOrNull() ?: return@runCatching
                        val icon = runCatching { binding.javaClass.getField("icon").get(binding) as? View }
                            .recoverCatching { binding.javaClass.getDeclaredField("icon").apply { isAccessible = true }.get(binding) as? View }
                            .getOrNull()
                        if (icon != null) {
                            putTag(icon, "topText", topText)
                            putTag(icon, "sliderType", "BrightnessSliderController")
                            runCatching {
                                thisObject.javaClass.getDeclaredMethod("updateIconProgress", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(thisObject, true)
                            }
                        }
                    }
                }
                result(res)
            }
        } ?: Log.e("HyperTweak", "Could not find Brightness onBindViewHolder")

        clzVolumeSlider?.declaredMethods?.firstOrNull { it.name == "onBindViewHolder" }?.let { method ->
            Log.d("HyperTweak", "Hooking Volume onBindViewHolder (for initTopText)")
            method.hook {
                val res = proceed()
                if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    val sameStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                    runCatching {
                        val holder = findHolder(thisObject) ?: return@runCatching
                        val topText = getTopTextFromHolder(holder) ?: return@runCatching
                        if (sameStyle) {
                            initTopText(topText)

                            // Link AnimateColorView for coloring
                            val binding = runCatching { holder.javaClass.getMethod("getBinding").invoke(holder) }
                                .recoverCatching { holder.javaClass.getDeclaredField("binding").apply { isAccessible = true }.get(holder) }
                                .getOrNull() ?: return@runCatching
                            val icon = runCatching { binding.javaClass.getField("icon").get(binding) as? View }
                                .recoverCatching { binding.javaClass.getDeclaredField("icon").apply { isAccessible = true }.get(binding) as? View }
                                .getOrNull()
                            if (icon != null) {
                                putTag(icon, "topText", topText)
                                putTag(icon, "sliderType", "VolumeSliderController")
                                runCatching {
                                    thisObject.javaClass.getDeclaredMethod("updateIconProgress", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(thisObject, true)
                                }
                            }
                        } else {
                            topText.post { topText.visibility = View.GONE }
                        }
                    }
                }
                result(res)
            }
        } ?: Log.e("HyperTweak", "Could not find Volume onBindViewHolder")

        // ─── ToggleSliderViewHolder: updateBlendBlur ───────────────────────────
        // OShape: after updateBlendBlur, re-apply topText blur/color style.
        // This ensures the topText style is always correct after any blur update.
        val clzViewHolder = "miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder".toClassOrNull()
        Log.d("HyperTweak", "clzViewHolder: ${clzViewHolder?.name}")
        clzViewHolder?.let { clz ->
            // updateBlendBlur is defined in parent MainPanelItemViewHolder, but overridden in ToggleSliderViewHolder
            val updateBlendBlurMethod = clz.declaredMethods.firstOrNull { it.name == "updateBlendBlur" }
                ?: clz.superclass?.declaredMethods?.firstOrNull { it.name == "updateBlendBlur" }
            updateBlendBlurMethod?.let { method ->
                Log.d("HyperTweak", "Hooking ToggleSliderViewHolder updateBlendBlur")
                method.hook {
                    val res = proceed()
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        runCatching {
                            val topText = getTopTextFromHolder(thisObject) ?: return@runCatching
                            applyTopTextStyle(topText)
                        }
                    }
                    result(res)
                }
            } ?: Log.e("HyperTweak", "Could not find updateBlendBlur method")
        }

        // ─── ToggleSliderViewHolder: setTopTextVisible ─────────────────────────
        // Force topText visible when percentage is enabled (for brightness + sameStyle volume)
        clzViewHolder?.declaredMethods?.firstOrNull { it.name == "setTopTextVisible" }?.let { method ->
            Log.d("HyperTweak", "Hooking ToggleSliderViewHolder setTopTextVisible")
            method.hook {
                if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    val sameStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                    // If sameStyle is false, volume slider calls this with z2=true because of SuperVolume
                    // But we want it to be false! Wait, Brightness doesn't call this.
                    // Actually, if we hooked onBindViewHolder, we don't even need this hook!
                    // Let's just override it: if sameStyle is false, we force it to false for Volume.
                    // But wait, the system might need it for accessibility label. 
                    // Let's just NOT hook it, as we manage visibility ourselves in onBindViewHolder.
                }
                result(proceed())
            }
        } ?: Log.e("HyperTweak", "Could not find setTopTextVisible")

        // ─── Brightness: updateIconProgress ───────────────────────────────────
        clzBrightnessSlider?.declaredMethods?.firstOrNull { it.name == "updateIconProgress" }?.let { method ->
            Log.d("HyperTweak", "Hooking Brightness updateIconProgress")
            method.hook {
                if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    updatePercentageText(thisObject, "BrightnessSliderController")
                }
                result(proceed())
            }
        } ?: Log.e("HyperTweak", "Could not find Brightness updateIconProgress")

        // ─── Volume: updateIconProgress ────────────────────────────────────────
        clzVolumeSlider?.declaredMethods?.firstOrNull { it.name == "updateIconProgress" }?.let { method ->
            Log.d("HyperTweak", "Hooking Volume updateIconProgress")
            method.hook {
                if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    updatePercentageText(thisObject, "VolumeSliderController")
                }
                result(proceed())
            }
        } ?: Log.e("HyperTweak", "Could not find Volume updateIconProgress")

        // ─── Volume: syncSystemVolume ──────────────────────────────────────────
        // OShape: after syncSystemVolume, update CC slider topText.
        // This fires when volume is externally changed (key press, etc.) after panel is open.
        clzVolumeSlider?.declaredMethods?.firstOrNull { it.name == "syncSystemVolume" }?.let { method ->
            Log.d("HyperTweak", "Hooking Volume syncSystemVolume")
            method.hook {
                val res = proceed()
                if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    val controller = thisObject
                    runCatching {
                        val topText = getTag(controller, "cached_topText") as? TextView
                            ?: run {
                                val holder = findHolder(controller) ?: return@runCatching
                                val tt = getTopTextFromHolder(holder) ?: return@runCatching
                                putTag(controller, "cached_topText", tt)
                                tt
                            }
                        val pct = calcVolumePercent(controller)
                        topText.post { topText.text = "$pct%" }
                    }.onFailure { t ->
                        Log.e("HyperTweak", "Error in syncSystemVolume hook", t)
                    }
                }
                result(res)
            }
        } ?: Log.e("HyperTweak", "Could not find Volume syncSystemVolume")

        // ─── Volume: updateSliderValue ─────────────────────────────────────────
        // OShape: after updateSliderValue, if NOT isOriginalVolumeCallback (arg[1]==false),
        // compute percent from valueToVolume(arg[0]) and update topText.
        // This fires during user drag — ensures live feedback without lag.
        clzVolumeSlider?.declaredMethods?.firstOrNull {
            it.name == "updateSliderValue" &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
            it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }?.let { method ->
            Log.d("HyperTweak", "Hooking Volume updateSliderValue")
            method.hook {
                val res = proceed()
                if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    val isOriginalVolumeCallback = args[1] as Boolean
                    if (!isOriginalVolumeCallback) {
                        val controller = thisObject
                        val sliderValue = args[0] as Int
                        runCatching {
                            val topText = getTag(controller, "cached_topText") as? TextView
                                ?: run {
                                    val holder = findHolder(controller) ?: return@runCatching
                                    val tt = getTopTextFromHolder(holder) ?: return@runCatching
                                    putTag(controller, "cached_topText", tt)
                                    tt
                                }
                            val pct = calcVolumePercentFromSliderValue(controller, sliderValue)
                            topText.text = "$pct%"
                        }.onFailure { t ->
                            Log.e("HyperTweak", "Error in updateSliderValue hook", t)
                        }
                    }
                }
                result(res)
            }
        } ?: Log.e("HyperTweak", "Could not find Volume updateSliderValue(int, boolean)")

        // ─── Brightness setInMirror ────────────────────────────────────────────
        clzBrightnessSlider?.declaredMethods?.firstOrNull {
            it.name == "setInMirror" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }?.let { method ->
            Log.d("HyperTweak", "Hooking Brightness setInMirror")
            method.hook {
                val inMirrorArg = args[0] as Boolean
                val currentInMirror = runCatching {
                    thisObject.javaClass.getDeclaredField("inMirror").apply { isAccessible = true }.get(thisObject) as Boolean
                }.getOrDefault(false)

                if (inMirrorArg == currentInMirror) {
                    return@hook result(proceed())
                }

                runCatching {
                    val holder = thisObject.javaClass.getMethod("getHolder").invoke(thisObject)
                        ?: return@runCatching
                    val binding = runCatching { holder.javaClass.getMethod("getBinding").invoke(holder) }.getOrNull()
                        ?: holder
                    val mirrorBlurProvider = runCatching {
                        binding.javaClass.getDeclaredField("mirrorBlurProvider").apply { isAccessible = true }.get(binding) as? View
                    }.getOrNull()
                    val topText = runCatching { binding.javaClass.getField("topText").get(binding) as? TextView }.getOrNull()
                        ?: runCatching { binding.javaClass.getMethod("getTopText").invoke(binding) as? TextView }.getOrNull()
                        ?: return@runCatching

                    val sameStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                    if (sameStyle && isBlurSupported(topText.context)) {
                        if (inMirrorArg) {
                            topText.chooseBackgroundBlurContainer(mirrorBlurProvider)
                        } else {
                            topText.chooseBackgroundBlurContainer(null)
                        }
                        if (brightnessBlendToken != null) {
                            topText.setMiViewBlurMode(3)
                            val clzMiBlurCompat = topText.context.classLoader.loadClass("miui.systemui.util.MiBlurCompat")
                            val clzColorBlendToken = topText.context.classLoader.loadClass("miuix.theme.token.ColorBlendToken")
                            clzMiBlurCompat.getMethod("setMiBackgroundBlendColors", View::class.java, clzColorBlendToken)
                               .invoke(null, topText, brightnessBlendToken)
                        }
                    } else {
                        topText.clearMiBlur()
                        topText.setTextColor(brightnessColor)
                    }
                }.onFailure { t ->
                    Log.e("HyperTweak", "Error in BrightnessSliderController.setInMirror hook", t)
                }
                result(proceed())
            }
        } ?: Log.e("HyperTweak", "Could not find Brightness setInMirror")

        // ─── Brightness Panel Animator Hooks (Transition Positions) ───────────
        val clzBrightnessAnimator = "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelAnimator".toClassOrNull()
        Log.d("HyperTweak", "clzBrightnessAnimator: ${clzBrightnessAnimator?.name}")

        clzBrightnessAnimator?.declaredMethods?.firstOrNull { it.name == "calculateViewValues" }?.let { method ->
            Log.d("HyperTweak", "Hooking BrightnessAnimator calculateViewValues")
            method.hook {
                val res = proceed()
                runCatching {
                    val fromView = thisObject.javaClass.getDeclaredField("fromView").apply { isAccessible = true }.get(thisObject) ?: return@runCatching
                    val fromText = fromView.javaClass.getMethod("getTopText").invoke(fromView) as? TextView ?: return@runCatching
                    fromLeft = fromText.left
                    fromTop = fromText.top
                    fromWidth = fromText.width
                    fromHeight = fromText.height

                    val getToView = thisObject.javaClass.getDeclaredMethod("getToView").apply { isAccessible = true }
                    val toView = getToView.invoke(thisObject) ?: return@runCatching
                    val sliderBinding = toView.javaClass.getMethod("getSliderBinding").invoke(toView) ?: return@runCatching
                    val toText = sliderBinding.javaClass.getField("topText").get(sliderBinding) as? TextView ?: return@runCatching
                    toLeft = toText.left
                    toTop = toText.top
                    toWidth = toText.width
                    toHeight = toText.height
                }.onFailure { t ->
                    Log.e("HyperTweak", "Error in calculateViewValues hook", t)
                }
                result(res)
            }
        } ?: Log.e("HyperTweak", "Could not find BrightnessAnimator calculateViewValues")

        clzBrightnessAnimator?.declaredMethods?.firstOrNull { it.name == "frameCallback" }?.let { method ->
            Log.d("HyperTweak", "Hooking BrightnessAnimator frameCallback")
            method.hook {
                val res = proceed()
                runCatching {
                    val fraction = thisObject.javaClass.getDeclaredField("size").apply { isAccessible = true }.get(thisObject) as Float
                    val left = fromLeft + (toLeft - fromLeft) * fraction
                    val top = fromTop + (toTop - fromTop) * fraction
                    val width = fromWidth + (toWidth - fromWidth) * fraction
                    val height = fromHeight + (toHeight - fromHeight) * fraction

                    val getToView = thisObject.javaClass.getDeclaredMethod("getToView").apply { isAccessible = true }
                    val toView = getToView.invoke(thisObject) ?: return@runCatching
                    val sliderBinding = toView.javaClass.getMethod("getSliderBinding").invoke(toView) ?: return@runCatching
                    val topText = sliderBinding.javaClass.getField("topText").get(sliderBinding) as? TextView ?: return@runCatching
                    topText.setLeftTopRightBottom(left.toInt(), top.toInt(), (left + width).toInt(), (top + height).toInt())
                }.onFailure { t ->
                    Log.e("HyperTweak", "Error in frameCallback hook", t)
                }
                result(res)
            }
        } ?: Log.e("HyperTweak", "Could not find BrightnessAnimator frameCallback")

        // ─── AnimateColorView Hooks ────────────────────────────────────────────
        val clzAnimateColorView = "miui.systemui.controlcenter.widget.AnimateColorView".toClassOrNull()
        Log.d("HyperTweak", "clzAnimateColorView: ${clzAnimateColorView?.name}")

        clzAnimateColorView?.declaredMethods?.firstOrNull { it.name == "recycle" }?.let { method ->
            Log.d("HyperTweak", "Hooking AnimateColorView recycle")
            method.hook {
                val view = thisObject as View
                removeTag(view, "topText")
                removeTag(view, "sliderType")
                val anim = removeTag(view, "topTextAnimator") as? android.animation.ValueAnimator
                anim?.cancel()
                result(proceed())
            }
        } ?: Log.e("HyperTweak", "Could not find AnimateColorView recycle")

        clzAnimateColorView?.declaredMethods?.firstOrNull {
            it.name == "updateIconColor" && it.parameterTypes.size == 5
        }?.let { method ->
            Log.d("HyperTweak", "Hooking AnimateColorView updateIconColor")
            method.hook {
                if (!Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    return@hook result(proceed())
                }
                val view = thisObject as View
                var topText = getTag(view, "topText") as? TextView
                var sliderType = getTag(view, "sliderType") as? String

                if (topText == null) {
                    var p = view.parent as? View
                    while (p != null && p is android.view.ViewGroup) {
                        val id = p.context.resources.getIdentifier("top_text", "id", p.context.packageName)
                        if (id != 0) {
                            val tv = p.findViewById<View>(id) as? TextView
                            if (tv != null) {
                                topText = tv
                                putTag(view, "topText", tv)
                                if (sliderType == null) {
                                    var isBrightness = false
                                    var currentParent: View? = p
                                    while (currentParent != null) {
                                        val parentClass = currentParent.javaClass.name
                                        if (parentClass.contains("brightness", ignoreCase = true)) {
                                            isBrightness = true
                                            break
                                        } else if (parentClass.contains("volume", ignoreCase = true)) {
                                            break
                                        }
                                        val parentId = currentParent.id
                                        if (parentId != View.NO_ID) {
                                            val entryName = runCatching { currentParent.context.resources.getResourceEntryName(parentId) }.getOrNull() ?: ""
                                            if (entryName.contains("brightness", ignoreCase = true)) {
                                                isBrightness = true
                                                break
                                            } else if (entryName.contains("volume", ignoreCase = true)) {
                                                break
                                            }
                                        }
                                        currentParent = currentParent.parent as? View
                                    }
                                    sliderType = if (isBrightness) "BrightnessSliderController" else "VolumeSliderController"
                                    putTag(view, "sliderType", sliderType)
                                }
                                break
                            }
                        }
                        p = p.parent as? View
                    }
                }

                if (topText == null || sliderType == null) {
                    return@hook result(proceed())
                }

                val fromToken = args[0]
                val toToken = args[1]
                val fromColorResId = args[2] as Int
                val toColorResId = args[3] as Int
                val animate = args[4] as Boolean

                val resolvedFromColor = runCatching { view.context.getColor(fromColorResId) }.getOrDefault(0xFFFFFFFF.toInt())
                val resolvedToColor = runCatching { view.context.getColor(toColorResId) }.getOrDefault(0xFFFFFFFF.toInt())

                val sameStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                
                // If sameStyle is false, the volume topText uses plain text and should not be blended
                if (!sameStyle && sliderType == "VolumeSliderController") {
                    return@hook result(proceed())
                }

                if (sliderType == "BrightnessSliderController") {
                    brightnessColor = resolvedToColor
                    brightnessBlendToken = toToken
                } else if (sliderType == "VolumeSliderController") {
                    volumeColor = resolvedToColor
                    volumeBlendToken = toToken
                }

                (getTag(view, "topTextAnimator") as? android.animation.ValueAnimator)?.cancel()

                runCatching {
                    if (sameStyle && isBlurSupported(view.context)) {
                        topText.setMiViewBlurMode(3)
                        if (animate) {
                            val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                                duration = 200
                                addUpdateListener { anim ->
                                    val fraction = anim.animatedValue as Float
                                    runCatching {
                                        val clzMiBlurCompat = topText.context.classLoader.loadClass("miui.systemui.util.MiBlurCompat")
                                        val clzColorBlendToken = topText.context.classLoader.loadClass("miuix.theme.token.ColorBlendToken")
                                        clzMiBlurCompat.getMethod("setMiBackgroundBlendColors", View::class.java, clzColorBlendToken, clzColorBlendToken, Float::class.javaPrimitiveType)
                                           .invoke(null, topText, fromToken, toToken, fraction)
                                    }
                                }
                            }
                            putTag(view, "topTextAnimator", animator)
                            animator.start()
                        } else {
                            runCatching {
                                val clzMiBlurCompat = topText.context.classLoader.loadClass("miui.systemui.util.MiBlurCompat")
                                val clzColorBlendToken = topText.context.classLoader.loadClass("miuix.theme.token.ColorBlendToken")
                                clzMiBlurCompat.getMethod("setMiBackgroundBlendColors", View::class.java, clzColorBlendToken)
                                   .invoke(null, topText, toToken)
                            }
                        }
                    } else {
                        // Plain style: animate real ARGB color
                        topText.clearMiBlur()
                        if (animate) {
                            val animator = android.animation.ValueAnimator.ofArgb(resolvedFromColor, resolvedToColor).apply {
                                duration = 200
                                addUpdateListener { anim ->
                                    topText.setTextColor(anim.animatedValue as Int)
                                }
                            }
                            putTag(view, "topTextAnimator", animator)
                            animator.start()
                        } else {
                            topText.setTextColor(resolvedToColor)
                        }
                    }
                }.onFailure { t ->
                    Log.e("HyperTweak", "Error in updateIconColor hook", t)
                }
                result(proceed())
            }
        } ?: Log.e("HyperTweak", "Could not find AnimateColorView updateIconColor")

        // ─── Volume Column Color & Blend Transitions ───────────────────────────
        val transitionClass = "com.android.systemui.miui.volume.VolumeColumn\$iconColorTransition\$2\$1".toClassOrNull()
        Log.d("HyperTweak", "transitionClass: ${transitionClass?.name}")

        transitionClass?.declaredMethods?.firstOrNull { it.name == "invoke" }?.let { method ->
            Log.d("HyperTweak", "Hooking iconColorTransition")
            method.hook {
                val fromColorList = args[0] as android.content.res.ColorStateList
                val toColorList = args[1] as android.content.res.ColorStateList
                val fraction = args[2] as Float

                runCatching {
                    val volumeColumn = thisObject.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(thisObject)
                    val superVolume = volumeColumn.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.get(volumeColumn) as TextView

                    val blendedColor = blendColors(fromColorList.defaultColor, toColorList.defaultColor, fraction)
                    superVolume.setTextColor(android.content.res.ColorStateList.valueOf(blendedColor))
                }.onFailure { t ->
                    Log.e("HyperTweak", "Error in VolumeColumn.iconColorTransition hook", t)
                }
                result(proceed())
            }
        } ?: Log.e("HyperTweak", "Could not find iconColorTransition")

        val blendTransitionClass = "com.android.systemui.miui.volume.VolumeColumn\$iconBlendColorTransition\$2\$1".toClassOrNull()
        Log.d("HyperTweak", "blendTransitionClass: ${blendTransitionClass?.name}")

        blendTransitionClass?.declaredMethods?.firstOrNull { it.name == "invoke" }?.let { method ->
            Log.d("HyperTweak", "Hooking iconBlendColorTransition")
            method.hook {
                val fromToken = args[0]
                val toToken = args[1]
                val fraction = args[2] as Float

                runCatching {
                    val volumeColumn = thisObject.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(thisObject)
                    val superVolume = volumeColumn.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.get(volumeColumn) as TextView

                    superVolume.setMiViewBlurMode(3)
                    val clzMiBlurCompat = superVolume.context.classLoader.loadClass("miui.systemui.util.MiBlurCompat")
                    val clzColorBlendToken = superVolume.context.classLoader.loadClass("miuix.theme.token.ColorBlendToken")
                    clzMiBlurCompat.getMethod("setMiBackgroundBlendColors", View::class.java, clzColorBlendToken, clzColorBlendToken, Float::class.javaPrimitiveType)
                       .invoke(null, superVolume, fromToken, toToken, fraction)
                }.onFailure { t ->
                    Log.e("HyperTweak", "Error in VolumeColumn.iconBlendColorTransition hook", t)
                }
                result(proceed())
            }
        } ?: Log.e("HyperTweak", "Could not find iconBlendColorTransition")

        // ─── VolumeColumn initColumn ───────────────────────────────────────────
        val clzVolumeColumn = "com.android.systemui.miui.volume.VolumeColumn".toClassOrNull()
        Log.d("HyperTweak", "clzVolumeColumn: ${clzVolumeColumn?.name}")

        clzVolumeColumn?.declaredMethods?.firstOrNull { method ->
            method.name == "initColumn" && method.parameterTypes.size == 6
        }?.let { method ->
            Log.d("HyperTweak", "Hooking VolumeColumn initColumn")
            method.hook {
                val res = proceed()
                val showPct = Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)
                val sameStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                if (showPct) {
                    val isExpanded = args[5] as? Boolean ?: false
                    val isInCCMainPage = runCatching {
                        thisObject.javaClass.getMethod("isInCCMainPage").invoke(thisObject) as Boolean
                    }.getOrDefault(true)
                    // We show the badge if it's not in CC (e.g. physical volume popup), OR badge mode (!sameStyle), OR if we are expanded
                    val shouldShowBadge = !isInCCMainPage || !sameStyle || isExpanded
                    if (shouldShowBadge) {
                        runCatching {
                            val superVolume = thisObject.javaClass.getDeclaredField("superVolume")
                                .apply { isAccessible = true }.get(thisObject) as? TextView ?: return@runCatching
                            if (superVolume.visibility != View.VISIBLE) {
                                superVolume.post {
                                    superVolume.visibility = View.VISIBLE
                                    superVolume.typeface = Typeface.DEFAULT_BOLD
                                }
                            }
                        }
                    } else {
                        // sameStyle and NOT expanded -> hide badge
                        runCatching {
                            val superVolume = thisObject.javaClass.getDeclaredField("superVolume")
                                .apply { isAccessible = true }.get(thisObject) as? TextView ?: return@runCatching
                            superVolume.post { superVolume.visibility = View.GONE }
                        }
                    }
                }
                result(res)
            }
        } ?: Log.e("HyperTweak", "Could not find VolumeColumn initColumn(6 params)")

        // ─── VolumePanelViewController ─────────────────────────────────────────
        val clzVolumeViewController = "com.android.systemui.miui.volume.VolumePanelViewController".toClassOrNull()
        Log.d("HyperTweak", "clzVolumeViewController: ${clzVolumeViewController?.name}")

        // ─── updateVolumeColumnH ─────────────────────────────────────
        clzVolumeViewController?.declaredMethods?.firstOrNull {
            it.name == "updateVolumeColumnH" &&
            it.parameterTypes.size == 1 &&
            it.parameterTypes[0].name.endsWith("VolumeColumn")
        }?.let { method ->
            Log.d("HyperTweak", "Hooking VolumeViewController updateVolumeColumnH")
            method.hook {
                val res = proceed() // run AFTER original
                if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    runCatching {
                        val mState = thisObject.javaClass.getDeclaredField("mState").apply { isAccessible = true }.get(thisObject) ?: return@runCatching
                        val mExpanded = thisObject.javaClass.getDeclaredField("mExpanded").apply { isAccessible = true }.get(thisObject) as Boolean
                        val activeStream = thisObject.javaClass.getDeclaredField("mActiveStream").apply { isAccessible = true }.get(thisObject) as Int
                        val column = args[0] ?: return@runCatching
                        
                        val stream = runCatching {
                            column.javaClass.getDeclaredField("stream").apply { isAccessible = true }.get(column) as Int
                        }.recoverCatching {
                            column.javaClass.getMethod("getStream").invoke(column) as Int
                        }.getOrDefault(-1)

                        if (stream >= 0) {
                            val states = mState.javaClass.getDeclaredField("states").apply { isAccessible = true }.get(mState)
                            val getMethod = states.javaClass.getMethod("get", Int::class.javaPrimitiveType ?: Int::class.java)
                            val streamState = getMethod.invoke(states, stream) ?: return@runCatching

                            val level = streamState.javaClass.getDeclaredField("level").apply { isAccessible = true }.get(streamState) as Int
                            val levelMax = streamState.javaClass.getDeclaredField("levelMax").apply { isAccessible = true }.get(streamState) as Int
                            val pct = if (levelMax > 0) Math.round(level * 1f / levelMax * 100f).coerceIn(0, 100) else 0

                            val columnSuperVolume = column.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.get(column) as? TextView
                            if (columnSuperVolume != null) {
                                columnSuperVolume.text = "$pct%"
                                columnSuperVolume.visibility = if (mExpanded) View.VISIBLE else View.GONE
                                if (mExpanded) {
                                    columnSuperVolume.typeface = Typeface.DEFAULT_BOLD
                                    applyTopTextStyle(columnSuperVolume)
                                }
                            }

                            if (!mExpanded) {
                                val isNeedShowDialog = thisObject.javaClass.getDeclaredField("mNeedShowDialog").apply { isAccessible = true }.get(thisObject) as Boolean
                                if (isNeedShowDialog && stream == activeStream) {
                                    val mSuperVolume = thisObject.javaClass.getDeclaredField("mSuperVolume").apply { isAccessible = true }.get(thisObject) as? TextView
                                    if (mSuperVolume != null) {
                                        mSuperVolume.text = "$pct%"
                                        mSuperVolume.typeface = Typeface.DEFAULT_BOLD
                                    }
                                }
                            }
                        }
                    }.onFailure { t ->
                        Log.e("HyperTweak", "Error in updateVolumeColumnH hook", t)
                    }
                }
                result(res)
            }
        } ?: Log.e("HyperTweak", "Could not find VolumeViewController updateVolumeColumnH")

        // ─── updateSuperVolumeText ─────────────────────────────────────────────
        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "updateSuperVolumeText" }?.let { method ->
            Log.d("HyperTweak", "Hooking VolumeViewController updateSuperVolumeText")
            method.hook {
                if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    val textView = args[0] as? TextView
                    if (textView != null) {
                        runCatching {
                            val mState = thisObject.javaClass.getDeclaredField("mState").apply { isAccessible = true }.get(thisObject) ?: return@runCatching
                            val mColumns = thisObject.javaClass.getDeclaredField("mColumns").apply { isAccessible = true }.get(thisObject) as List<*>
                            
                            // Find which stream/column this textView belongs to
                            var foundStream = -1
                            val mSuperVolume = thisObject.javaClass.getDeclaredField("mSuperVolume").apply { isAccessible = true }.get(thisObject) as? TextView
                            
                            if (textView === mSuperVolume) {
                                foundStream = thisObject.javaClass.getDeclaredField("mActiveStream").apply { isAccessible = true }.get(thisObject) as Int
                            } else {
                                for (col in mColumns) {
                                    if (col != null) {
                                        val sv = col.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.get(col)
                                        if (sv === textView) {
                                            foundStream = runCatching {
                                                col.javaClass.getDeclaredField("stream").apply { isAccessible = true }.get(col) as Int
                                            }.recoverCatching {
                                                col.javaClass.getMethod("getStream").invoke(col) as Int
                                            }.getOrDefault(-1)
                                            break
                                        }
                                    }
                                }
                            }

                            if (foundStream >= 0) {
                                val states = mState.javaClass.getDeclaredField("states").apply { isAccessible = true }.get(mState)
                                val getMethod = states.javaClass.getMethod("get", Int::class.javaPrimitiveType ?: Int::class.java)
                                val streamState = getMethod.invoke(states, foundStream) ?: return@runCatching
                                val level = streamState.javaClass.getDeclaredField("level").apply { isAccessible = true }.get(streamState) as Int
                                val levelMax = streamState.javaClass.getDeclaredField("levelMax").apply { isAccessible = true }.get(streamState) as Int
                                val pct = if (levelMax > 0) Math.round(level * 1f / levelMax * 100f).coerceIn(0, 100) else 0

                                textView.text = "$pct%"
                                textView.visibility = View.VISIBLE
                                textView.typeface = Typeface.DEFAULT_BOLD
                                result(null) // skip original
                                return@hook result(null)
                            }
                        }.onFailure { t ->
                            Log.e("HyperTweak", "Error in updateSuperVolumeText hook", t)
                        }
                    }
                }
                result(proceed())
            }
        } ?: Log.e("HyperTweak", "Could not find VolumeViewController updateSuperVolumeText")

        // ─── updateSuperVolumeViewColor ────────────────────────────────────────
        // OShape: after updateSuperVolumeViewColor, set typeface to bold on mSuperVolume.
        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "updateSuperVolumeViewColor" }?.let { method ->
            Log.d("HyperTweak", "Hooking VolumeViewController updateSuperVolumeViewColor")
            method.hook {
                val res = proceed()
                if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    runCatching {
                        val mSuperVolume = thisObject.javaClass.getDeclaredField("mSuperVolume").apply { isAccessible = true }.get(thisObject) as? TextView ?: return@runCatching
                        mSuperVolume.typeface = Typeface.DEFAULT_BOLD
                    }
                }
                result(res)
            }
        } ?: Log.e("HyperTweak", "Could not find VolumeViewController updateSuperVolumeViewColor")

        // ─── updateSuperVolumeView (BEFORE) ───────────────────────────────────
        // Hook BEFORE to toggle mSuperVolumeBg visibility based on isExpanded.
        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "updateSuperVolumeView" }?.let { method ->
            Log.d("HyperTweak", "Hooking VolumeViewController updateSuperVolumeView (BEFORE)")
            method.hook {
                if (!Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    return@hook result(proceed())
                }

                runCatching {
                    val mExpanded = thisObject.javaClass.getDeclaredField("mExpanded").apply { isAccessible = true }.get(thisObject) as Boolean
                    val mSuperVolumeBg = thisObject.javaClass.getDeclaredField("mSuperVolumeBg").apply { isAccessible = true }.get(thisObject) as? View

                    // Badge mode & collapsed physical volume dialog show the badge background
                    mSuperVolumeBg?.visibility = if (mExpanded) View.GONE else View.VISIBLE
                }.onFailure { t ->
                    Log.e("HyperTweak", "Error in VolumePanelViewController.updateSuperVolumeView BEFORE hook", t)
                }

                // Proceed with the original method — it manages the actual superVolume text/state
                result(proceed())
            }
        } ?: Log.e("HyperTweak", "Could not find VolumeViewController updateSuperVolumeView")

        // ─── BrightnessPanelSliderDelegate (Expanded Brightness) ────────────────
        val clzBrightnessPanelDelegate = "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate".toClassOrNull()
        clzBrightnessPanelDelegate?.declaredMethods?.firstOrNull { it.name == "prepareShow" }?.let { method ->
            Log.d("HyperTweak", "Hooking BrightnessPanelSliderDelegate prepareShow")
            method.hook {
                val res = proceed()
                if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    runCatching {
                        val binding = thisObject.javaClass.getDeclaredField("binding").apply { isAccessible = true }.get(thisObject)
                        val toggleSlider = binding.javaClass.getField("toggleSlider").apply { isAccessible = true }.get(binding)
                        val topText = toggleSlider.javaClass.getField("topText").apply { isAccessible = true }.get(toggleSlider) as TextView
                        val icon = toggleSlider.javaClass.getField("icon").apply { isAccessible = true }.get(toggleSlider) as View
                        
                        initTopText(topText)
                        applyTopTextStyle(topText)
                        
                        putTag(icon, "topText", topText)
                        putTag(icon, "sliderType", "BrightnessSliderController")
                        
                        // Re-trigger updateIconProgress to apply colors immediately!
                        thisObject.javaClass.getDeclaredMethod("updateIconProgress", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(thisObject, true)
                    }.onFailure { t ->
                        Log.e("HyperTweak", "Error in BrightnessPanelSliderDelegate.prepareShow hook", t)
                    }
                }
                result(res)
            }
        } ?: Log.e("HyperTweak", "Could not find BrightnessPanelSliderDelegate prepareShow")

        clzBrightnessPanelDelegate?.declaredMethods?.firstOrNull { it.name == "updateIconProgress" }?.let { method ->
            Log.d("HyperTweak", "Hooking BrightnessPanelSliderDelegate updateIconProgress")
            method.hook {
                val res = proceed()
                if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                    runCatching {
                        val binding = thisObject.javaClass.getDeclaredField("binding").apply { isAccessible = true }.get(thisObject)
                        val toggleSlider = binding.javaClass.getField("toggleSlider").apply { isAccessible = true }.get(binding)
                        val topText = toggleSlider.javaClass.getField("topText").apply { isAccessible = true }.get(toggleSlider) as TextView
                        val slider = toggleSlider.javaClass.getField("slider").apply { isAccessible = true }.get(toggleSlider) as android.widget.SeekBar
                        
                        val level = slider.progress
                        val maxLevel = slider.max
                        val pct = if (maxLevel > 0) Math.round(level * 1f / maxLevel * 100f).coerceIn(0, 100) else 0
                        topText.text = "$pct%"
                    }.onFailure { t ->
                        Log.e("HyperTweak", "Error updating BrightnessPanelSliderDelegate percentage", t)
                    }
                }
                result(res)
            }
        } ?: Log.e("HyperTweak", "Could not find BrightnessPanelSliderDelegate updateIconProgress")
    }
}
