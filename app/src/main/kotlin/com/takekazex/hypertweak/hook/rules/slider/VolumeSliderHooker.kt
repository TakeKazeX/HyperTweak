package com.takekazex.hypertweak.hook.rules.slider
 
import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.applyTopTextStyle
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.blendColors
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.calcVolumePercent
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.calcVolumePercentFromSliderValue
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.findHolder
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.getTag
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.getTopTextFromHolder
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.initTopText
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.putTag
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.updatePercentageText

class VolumeSliderHooker(
    private val parent: SliderPercentageHooker
) : DynamicHooker() {

    @Volatile
    private var isVolumeViewHooked = false

    // Cached reflection fields for iconColorTransition / iconBlendColorTransition (hot path: every animation frame)
    private var cachedThis0Field: java.lang.reflect.Field? = null
    private var cachedSuperVolumeField: java.lang.reflect.Field? = null
    private var cachedMiBlurCompatClass: Class<*>? = null
    private var cachedColorBlendTokenClass: Class<*>? = null
    private var cachedTokenBlendMethod: java.lang.reflect.Method? = null

    // Cached field refs for VolumePanelViewController hooks (hot path: every volume state update)
    private var cachedVpcFieldsLoaded = false
    private var vpcField_mState: java.lang.reflect.Field? = null
    private var vpcField_mExpanded: java.lang.reflect.Field? = null
    private var vpcField_mActiveStream: java.lang.reflect.Field? = null
    private var vpcField_mSuperVolume: java.lang.reflect.Field? = null
    private var vpcField_mSuperVolumeBg: java.lang.reflect.Field? = null
    private var vpcField_mVolumeView: java.lang.reflect.Field? = null
    private var vpcField_isControlCenterPanel: java.lang.reflect.Field? = null
    private var vpcField_mColumns: java.lang.reflect.Field? = null

    // Cached field refs for stream state
    private var cachedStreamStateFieldsLoaded = false
    private var ssField_states: java.lang.reflect.Field? = null
    private var ssField_level: java.lang.reflect.Field? = null
    private var ssField_levelMax: java.lang.reflect.Field? = null
    private var ssMethod_get: java.lang.reflect.Method? = null

    // Cached field refs for VolumeColumn
    private var cachedVolumeColumnField: java.lang.reflect.Field? = null
    private var cachedColumnStreamField: java.lang.reflect.Field? = null
    private var cachedColumnStreamGetter: java.lang.reflect.Method? = null

    // Cached GradientDrawable for badge background
    private var cachedBadgeBgColor: Int = Int.MIN_VALUE
    private var cachedBadgeDrawable: android.graphics.drawable.GradientDrawable? = null
    private var cachedVolumeRadiusMethod: java.lang.reflect.Method? = null
    private var cachedVolumeRadiusLoaded = false

    override fun onHook() {
        val clzVolumeSlider = parent.resolveClass("miui.systemui.controlcenter.panel.main.volume.VolumeSliderController")
        Log.d("HyperTweak", "VolumeSliderHooker onHook - clzVolumeSlider: ${clzVolumeSlider?.name}")

        clzVolumeSlider?.declaredMethods?.firstOrNull { it.name == "onBindViewHolder" }?.let { method ->
            Log.d("HyperTweak", "Hooking Volume onBindViewHolder")
            method.hook {
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        runCatching {
                            val holder = findHolder(param.thisObject) ?: return@runCatching
                            val topText = getTopTextFromHolder(holder) ?: return@runCatching
                            initTopText(topText)
                            putTag(topText, "sliderType", "VolumeSliderController")
                            applyTopTextStyle(topText, force = true, sliderType = "VolumeSliderController")
                            updatePercentageText(param.thisObject, "VolumeSliderController")
                        }
                    }
                }
            }
        }

        clzVolumeSlider?.declaredMethods?.firstOrNull { it.name == "updateIconProgress" }?.let { method ->
            method.hook {
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        updatePercentageText(param.thisObject, "VolumeSliderController")
                    }
                }
            }
        }

        clzVolumeSlider?.declaredMethods?.firstOrNull { it.name == "syncSystemVolume" }?.let { method ->
            method.hook {
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        val controller = param.thisObject
                        runCatching {
                            val topText = getTag(controller, "cached_topText") as? TextView
                                ?: run {
                                    val holder = findHolder(controller) ?: return@runCatching
                                    val tt = getTopTextFromHolder(holder) ?: return@runCatching
                                    putTag(controller, "cached_topText", tt)
                                    tt
                                }
                            val pct = calcVolumePercent(controller)
                            topText.post {
                                topText.text = "$pct%"
                                applyTopTextStyle(topText, sliderType = "VolumeSliderController")
                            }
                        }.onFailure { t ->
                            Log.e("HyperTweak", "Error in syncSystemVolume hook", t)
                        }
                    }
                }
            }
        }

        clzVolumeSlider?.declaredMethods?.firstOrNull {
            it.name == "updateSliderValue" &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
            it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }?.let { method ->
            method.hook {
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        val isOriginalVolumeCallback = param.args[1] as Boolean
                        if (!isOriginalVolumeCallback) {
                            val controller = param.thisObject
                            val sliderValue = param.args[0] as Int
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
                                applyTopTextStyle(topText, sliderType = "VolumeSliderController")
                            }.onFailure { t ->
                                Log.e("HyperTweak", "Error in updateSliderValue hook", t)
                            }
                        }
                    }
                }
            }
        }

        // ─── Volume Column Color & Blend Transitions ───────────────────────────
        val transitionClass = parent.resolveClass("com.android.systemui.miui.volume.VolumeColumn\$iconColorTransition\$2\$1")
        transitionClass?.declaredMethods?.firstOrNull { it.name == "invoke" }?.let { method ->
            method.hook {
                before { param ->
                    val fromColorList = param.args[0] as android.content.res.ColorStateList
                    val toColorList = param.args[1] as android.content.res.ColorStateList
                    val fraction = param.args[2] as Float

                    runCatching {
                        val thisObject = param.thisObject
                        val this0Field = cachedThis0Field ?: thisObject.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.also { cachedThis0Field = it }
                        val volumeColumn = this0Field.get(thisObject)
                        val svField = cachedSuperVolumeField ?: volumeColumn.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.also { cachedSuperVolumeField = it }
                        val superVolume = svField.get(volumeColumn) as TextView

                        val sameStyleVolume = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                        if (sameStyleVolume) {
                            val blendedColor = blendColors(fromColorList.defaultColor, toColorList.defaultColor, fraction)
                            ColorOverrideLock.isSettingColor.set(true)
                            runCatching {
                                superVolume.setTextColor(android.content.res.ColorStateList.valueOf(blendedColor))
                            }
                            ColorOverrideLock.isSettingColor.set(false)
                        } else {
                            val context = superVolume.context
                            val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                            val textColor = if (isDark) android.graphics.Color.parseColor("#B3FFFFFF") else android.graphics.Color.parseColor("#B3000000")
                            superVolume.setTextColor(textColor)
                        }
                    }.onFailure { t ->
                        Log.e("HyperTweak", "Error in VolumeColumn.iconColorTransition hook", t)
                    }
                }
            }
        }

        val blendTransitionClass = parent.resolveClass("com.android.systemui.miui.volume.VolumeColumn\$iconBlendColorTransition\$2\$1")
        blendTransitionClass?.declaredMethods?.firstOrNull { it.name == "invoke" }?.let { method ->
            method.hook {
                before { param ->
                    val fromToken = param.args[0]
                    val toToken = param.args[1]
                    val fraction = param.args[2] as Float

                    runCatching {
                        val thisObject = param.thisObject
                        val this0Field = cachedThis0Field ?: thisObject.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.also { cachedThis0Field = it }
                        val volumeColumn = this0Field.get(thisObject)
                        val svField = cachedSuperVolumeField ?: volumeColumn.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.also { cachedSuperVolumeField = it }
                        val superVolume = svField.get(volumeColumn) as TextView

                        val sameStyleVolume = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                        if (sameStyleVolume) {
                            superVolume.setMiViewBlurMode(3)
                            val clzMiBlurCompat = cachedMiBlurCompatClass ?: superVolume.context.classLoader.loadClass("miui.systemui.util.MiBlurCompat").also { cachedMiBlurCompatClass = it }
                            val clzColorBlendToken = cachedColorBlendTokenClass ?: superVolume.context.classLoader.loadClass("miuix.theme.token.ColorBlendToken").also { cachedColorBlendTokenClass = it }
                            val blendMethod = cachedTokenBlendMethod ?: clzMiBlurCompat.getMethod("setMiBackgroundBlendColors", View::class.java, clzColorBlendToken, clzColorBlendToken, Float::class.javaPrimitiveType).also { cachedTokenBlendMethod = it }
                            blendMethod.invoke(null, superVolume, fromToken, toToken, fraction)
                        } else {
                            superVolume.clearMiBlur()
                        }
                    }.onFailure { t ->
                        Log.e("HyperTweak", "Error in VolumeColumn.iconBlendColorTransition hook", t)
                    }
                }
            }
        }

        // ─── VolumeColumn initColumn ───────────────────────────────────────────
        val clzVolumeColumn = parent.resolveClass("com.android.systemui.miui.volume.VolumeColumn")
        clzVolumeColumn?.declaredMethods?.firstOrNull { method ->
            method.name == "initColumn" && method.parameterTypes.size == 6
        }?.let { method ->
            method.hook {
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        val sameStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                        val isExpanded = param.args[5] as? Boolean ?: false
                        val thisObject = param.thisObject
                        val isInCCMainPage = runCatching {
                            thisObject.javaClass.getMethod("isInCCMainPage").invoke(thisObject) as Boolean
                        }.getOrDefault(true)
                        
                        val shouldShow = if (isInCCMainPage) !sameStyle else isExpanded
                        runCatching {
                            val superVolume = thisObject.javaClass.getDeclaredField("superVolume")
                                .apply { isAccessible = true }.get(thisObject) as? TextView ?: return@runCatching
                            superVolume.visibility = if (shouldShow) View.VISIBLE else View.INVISIBLE
                            superVolume.typeface = Typeface.DEFAULT_BOLD
                            if (sameStyle) {
                                putTag(superVolume, "sliderType", "VolumePanelViewController")
                                val activeColor = SliderHookHelper.getActiveColor(superVolume.context, "VolumePanelViewController")
                                ColorOverrideLock.isSettingColor.set(true)
                                runCatching {
                                    superVolume.setTextColor(activeColor)
                                }
                                ColorOverrideLock.isSettingColor.set(false)
                            } else {
                                val context = superVolume.context
                                val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                                val textColor = if (isDark) android.graphics.Color.parseColor("#B3FFFFFF") else android.graphics.Color.parseColor("#B3000000")
                                superVolume.setTextColor(textColor)
                            }
                        }
                    }
                }
            }
        }

        // ─── VolumePanelViewController ─────────────────────────────────────────
        val clzVolumeViewController = parent.resolveClass("com.android.systemui.miui.volume.VolumePanelViewController")

        // Helper: apply badge theme colors. Called both at init, update, and show.
        fun loadVpcFields(thisObject: Any) {
            if (cachedVpcFieldsLoaded) return
            val clz = thisObject.javaClass
            vpcField_mState = clz.getDeclaredField("mState").apply { isAccessible = true }
            vpcField_mExpanded = clz.getDeclaredField("mExpanded").apply { isAccessible = true }
            vpcField_mActiveStream = clz.getDeclaredField("mActiveStream").apply { isAccessible = true }
            vpcField_mSuperVolume = clz.getDeclaredField("mSuperVolume").apply { isAccessible = true }
            vpcField_mSuperVolumeBg = clz.getDeclaredField("mSuperVolumeBg").apply { isAccessible = true }
            vpcField_mVolumeView = clz.getDeclaredField("mVolumeView").apply { isAccessible = true }
            vpcField_isControlCenterPanel = runCatching { clz.getDeclaredField("isControlCenterPanel").apply { isAccessible = true } }.getOrNull()
            vpcField_mColumns = runCatching { clz.getDeclaredField("mColumns").apply { isAccessible = true } }.getOrNull()
            cachedVpcFieldsLoaded = true
        }

        fun applyBadgeThemeColors(thisObject: Any) {
            if (!Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) return
            runCatching {
                loadVpcFields(thisObject)
                val mSuperVolumeBg = vpcField_mSuperVolumeBg?.get(thisObject) as? View
                val mSuperVolume = vpcField_mSuperVolume?.get(thisObject) as? TextView
                val mExpanded = vpcField_mExpanded?.get(thisObject) as Boolean
                if (mSuperVolumeBg != null && mSuperVolume != null) {
                    val context = mSuperVolumeBg.context
                    val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    val bgColor = if (isDark) android.graphics.Color.parseColor("#3A3A3C") else android.graphics.Color.parseColor("#E5E5E5")

                    // Cached radius method and reusable GradientDrawable
                    val radiusMethod = cachedVolumeRadiusMethod ?: run {
                        if (!cachedVolumeRadiusLoaded) {
                            val m = runCatching {
                                val clz = context.classLoader.loadClass("com.android.systemui.miui.volume.VolumeColumnRes")
                                clz.getMethod("getRadius", android.content.Context::class.java)
                            }.getOrNull()
                            cachedVolumeRadiusMethod = m
                            cachedVolumeRadiusLoaded = true
                            m
                        } else null
                    }
                    val radius = (if (radiusMethod != null) {
                        runCatching { radiusMethod.invoke(null, context) as Int }.getOrNull()?.toFloat()
                    } else null) ?: (20f * context.resources.displayMetrics.density)

                    // Reuse GradientDrawable when background color hasn't changed
                    val existing = cachedBadgeDrawable
                    if (existing != null && cachedBadgeBgColor == bgColor) {
                        existing.cornerRadius = radius
                        mSuperVolumeBg.background = existing
                    } else {
                        val newBg = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            setColor(bgColor)
                            cornerRadius = radius
                        }
                        cachedBadgeDrawable = newBg
                        cachedBadgeBgColor = bgColor
                        mSuperVolumeBg.background = newBg
                    }
                    mSuperVolumeBg.backgroundTintList = null

                    // Clear the TextView's background/tint to let the capsule's background show through
                    val paddingLeft = mSuperVolume.paddingLeft
                    val paddingTop = mSuperVolume.paddingTop
                    val paddingRight = mSuperVolume.paddingRight
                    val paddingBottom = mSuperVolume.paddingBottom
                    mSuperVolume.background = null
                    mSuperVolume.backgroundTintList = null
                    mSuperVolume.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)

                    // Style text color and typeface
                    mSuperVolume.typeface = Typeface.DEFAULT_BOLD
                    val sameStyleVolume = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                    if (sameStyleVolume) {
                        putTag(mSuperVolume, "sliderType", "VolumePanelViewController")
                        val activeColor = SliderHookHelper.getActiveColor(context, "VolumePanelViewController")
                        ColorOverrideLock.isSettingColor.set(true)
                        runCatching {
                            mSuperVolume.setTextColor(activeColor)
                        }
                        ColorOverrideLock.isSettingColor.set(false)
                    } else {
                        val textColor = if (isDark) android.graphics.Color.parseColor("#B3FFFFFF") else android.graphics.Color.parseColor("#B3000000")
                        mSuperVolume.setTextColor(textColor)
                    }

                    // Maintain visibility based on expanded state
                    val badgeVisibility = if (mExpanded) View.GONE else View.VISIBLE
                    mSuperVolumeBg.visibility = badgeVisibility
                    mSuperVolume.visibility = badgeVisibility

                    // Dynamic fallback hook for updateSuperVolumeVisibility
                    if (!isVolumeViewHooked) {
                        runCatching {
                            val mVolumeView = vpcField_mVolumeView?.get(thisObject)
                            if (mVolumeView != null) {
                                val clz = mVolumeView.javaClass
                                clz.declaredMethods.firstOrNull {
                                    it.name == "updateSuperVolumeVisibility" &&
                                    it.parameterTypes.size == 1 &&
                                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                                }?.let { method ->
                                    method.hook {
                                        before { param ->
                                            if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                                                val view = param.thisObject
                                                val isExpanded = runCatching {
                                                    view.javaClass.getMethod("isExpanded").invoke(view) as Boolean
                                                }.getOrDefault(false)
                                                val inCCMainPage = runCatching {
                                                    view.javaClass.getMethod("inCCMainPage").invoke(view) as Boolean
                                                }.getOrNull()
                                                if (inCCMainPage != true) {
                                                    param.args[0] = !isExpanded
                                                }
                                            }
                                        }
                                    }
                                    Log.d("HyperTweak", "Successfully hooked updateSuperVolumeVisibility dynamically at runtime")
                                }
                                isVolumeViewHooked = true
                            }
                        }
                    }
                }
            }.onFailure { t ->
                Log.e("HyperTweak", "Error applying badge theme colors", t)
            }
        }

        // Helper: update badge text with the active stream's current percentage
        fun loadStreamStateFields(mState: Any) {
            if (cachedStreamStateFieldsLoaded) return
            val stateClz = mState.javaClass
            ssField_states = stateClz.getDeclaredField("states").apply { isAccessible = true }
            val statesObj = ssField_states!!.get(mState)
            if (statesObj != null) {
                ssMethod_get = statesObj.javaClass.getMethod("get", Int::class.javaPrimitiveType ?: Int::class.java)
                // Probe a sample element to cache level/levelMax fields
                val sample = runCatching { ssMethod_get!!.invoke(statesObj, 0) }.getOrNull()
                if (sample != null) {
                    ssField_level = sample.javaClass.getDeclaredField("level").apply { isAccessible = true }
                    ssField_levelMax = sample.javaClass.getDeclaredField("levelMax").apply { isAccessible = true }
                }
            }
            cachedStreamStateFieldsLoaded = true
        }

        fun updateBadgeText(thisObject: Any, activeStream: Int) {
            if (!Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) return
            runCatching {
                loadVpcFields(thisObject)
                val mState = vpcField_mState?.get(thisObject) ?: return
                loadStreamStateFields(mState)
                val states = ssField_states?.get(mState) ?: return
                val streamState = ssMethod_get?.invoke(states, activeStream) ?: return

                val level = (ssField_level?.get(streamState) as? Int) ?: 0
                val levelMax = (ssField_levelMax?.get(streamState) as? Int) ?: 0
                val pct = if (levelMax > 0) Math.round(level * 1f / levelMax * 100f).coerceIn(0, 100) else 0

                val mSuperVolume = vpcField_mSuperVolume?.get(thisObject) as? TextView
                if (mSuperVolume != null) {
                    mSuperVolume.text = "$pct%"
                }
            }.onFailure { t ->
                Log.e("HyperTweak", "Error updating badge text", t)
            }
        }

        // Apply at init so the color is ready before the first show
        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "initSuperVolumeColor" }?.let { method ->
            method.hook {
                intercept { chain ->
                    applyBadgeThemeColors(chain.thisObject)
                    null
                }
            }
        }

        // Apply right before the dialog becomes visible — fixes first-press white-bg and cold start visibility bugs
        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "showVolumePanelH" }?.let { method ->
            method.hook {
                before { param ->
                    val activeStream = param.args[0] as Int
                    updateBadgeText(param.thisObject, activeStream)
                    applyBadgeThemeColors(param.thisObject)
                }
            }
        }

        clzVolumeViewController?.declaredMethods?.firstOrNull {
            it.name == "updateVolumeColumnH" &&
            it.parameterTypes.size == 1 &&
            it.parameterTypes[0].name.endsWith("VolumeColumn")
        }?.let { method ->
            method.hook {
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        runCatching {
                            val thisObject = param.thisObject
                            loadVpcFields(thisObject)
                            val mState = vpcField_mState?.get(thisObject) ?: return@runCatching
                            val mExpanded = vpcField_mExpanded?.get(thisObject) as Boolean
                            val activeStream = vpcField_mActiveStream?.get(thisObject) as Int
                            val column = param.args[0] ?: return@runCatching
                            loadStreamStateFields(mState)

                            // Cache VolumeColumn field refs
                            val colStreamField = cachedColumnStreamField ?: runCatching {
                                column.javaClass.getDeclaredField("stream").apply { isAccessible = true }.also { cachedColumnStreamField = it }
                            }.getOrNull()
                            val colStreamGetter = if (colStreamField == null && cachedColumnStreamGetter == null) {
                                runCatching { column.javaClass.getMethod("getStream").also { cachedColumnStreamGetter = it } }.getOrNull()
                            } else cachedColumnStreamGetter
                            val stream = if (colStreamField != null) {
                                runCatching { colStreamField.get(column) as Int }.getOrDefault(-1)
                            } else if (colStreamGetter != null) {
                                runCatching { colStreamGetter.invoke(column) as Int }.getOrDefault(-1)
                            } else -1

                            if (stream >= 0) {
                                val states = ssField_states?.get(mState) ?: return@runCatching
                                val streamState = ssMethod_get?.invoke(states, stream) ?: return@runCatching
  
                                val level = (ssField_level?.get(streamState) as? Int) ?: 0
                                val levelMax = (ssField_levelMax?.get(streamState) as? Int) ?: 0
                                val pct = if (levelMax > 0) Math.round(level * 1f / levelMax * 100f).coerceIn(0, 100) else 0

                                val sameStyleVolume = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                                val colSuperVolField = cachedVolumeColumnField ?: column.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.also { cachedVolumeColumnField = it }
                                val columnSuperVolume = colSuperVolField.get(column) as? TextView
                                if (columnSuperVolume != null) {
                                    columnSuperVolume.text = "$pct%"
                                    val isControlCenter = (vpcField_isControlCenterPanel?.get(thisObject) as? Boolean) ?: false
                                    val shouldShowInner = if (isControlCenter) !sameStyleVolume else mExpanded
                                    columnSuperVolume.visibility = if (shouldShowInner) View.VISIBLE else View.INVISIBLE
                                    if (shouldShowInner) {
                                        columnSuperVolume.typeface = Typeface.DEFAULT_BOLD
                                        if (sameStyleVolume) {
                                            putTag(columnSuperVolume, "sliderType", "VolumePanelViewController")
                                            val activeColor = SliderHookHelper.getActiveColor(columnSuperVolume.context, "VolumePanelViewController")
                                            ColorOverrideLock.isSettingColor.set(true)
                                            runCatching {
                                                columnSuperVolume.setTextColor(activeColor)
                                            }
                                            ColorOverrideLock.isSettingColor.set(false)
                                        } else {
                                            val context = columnSuperVolume.context
                                            val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                                            val textColor = if (isDark) android.graphics.Color.parseColor("#B3FFFFFF") else android.graphics.Color.parseColor("#B3000000")
                                            columnSuperVolume.setTextColor(textColor)
                                        }
                                    }
                                }

                                if (!mExpanded) {
                                    if (stream == activeStream) {
                                        val mSuperVolume = vpcField_mSuperVolume?.get(thisObject) as? TextView
                                        if (mSuperVolume != null) {
                                            mSuperVolume.text = "$pct%"
                                            mSuperVolume.typeface = Typeface.DEFAULT_BOLD
                                            mSuperVolume.visibility = View.VISIBLE

                                            if (sameStyleVolume) {
                                                putTag(mSuperVolume, "sliderType", "VolumePanelViewController")
                                                val activeColor = SliderHookHelper.getActiveColor(mSuperVolume.context, "VolumePanelViewController")
                                                ColorOverrideLock.isSettingColor.set(true)
                                                runCatching {
                                                    mSuperVolume.setTextColor(activeColor)
                                                }
                                                ColorOverrideLock.isSettingColor.set(false)
                                            } else {
                                                val context = mSuperVolume.context
                                                val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                                                val textColor = if (isDark) android.graphics.Color.parseColor("#B3FFFFFF") else android.graphics.Color.parseColor("#B3000000")
                                                mSuperVolume.setTextColor(textColor)
                                            }
                                        }
                                        // Ensure badge background follows system theme on every state update
                                        applyBadgeThemeColors(thisObject)
                                    }
                                }
                            }
                        }.onFailure { t ->
                            Log.e("HyperTweak", "Error in updateVolumeColumnH hook", t)
                        }
                    }
                }
            }
        }

        clzVolumeViewController?.declaredMethods?.firstOrNull {
            it.name == "updateSuperVolumeView" &&
            it.parameterTypes.size == 1 &&
            it.parameterTypes[0].name.endsWith("VolumeColumn")
        }?.let { method ->
            method.hook {
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        runCatching {
                            val thisObject = param.thisObject
                            val column = param.args[0] ?: return@runCatching
                            loadVpcFields(thisObject)
                            val mExpanded = vpcField_mExpanded?.get(thisObject) as Boolean

                            val colSuperVolField = cachedVolumeColumnField ?: column.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.also { cachedVolumeColumnField = it }
                            val superVolume = colSuperVolField.get(column) as? TextView
                            if (superVolume != null) {
                                val sameStyleVolume = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                                val isControlCenter = (vpcField_isControlCenterPanel?.get(thisObject) as? Boolean) ?: false
                                val shouldShowInner = if (isControlCenter) !sameStyleVolume else mExpanded
                                superVolume.visibility = if (shouldShowInner) View.VISIBLE else View.INVISIBLE
                            }
                        }
                    }
                }
            }
        }

        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "updateSuperVolumeText" }?.let { method ->
            method.hook {
                intercept { chain ->
                    val param = chain.args
                    val thisObject = chain.thisObject
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        val textView = param[0] as? TextView
                        if (textView != null) {
                            val skipped = runCatching {
                                loadVpcFields(thisObject)
                                val mState = vpcField_mState?.get(thisObject) ?: return@runCatching false
                                loadStreamStateFields(mState)
                                val mColumns = vpcField_mColumns?.get(thisObject) as? List<*> ?: return@runCatching false

                                var foundStream = -1
                                val mSuperVolume = vpcField_mSuperVolume?.get(thisObject) as? TextView
                                
                                if (textView === mSuperVolume) {
                                    foundStream = vpcField_mActiveStream?.get(thisObject) as Int
                                } else {
                                    val colSuperVolField = cachedVolumeColumnField
                                    for (col in mColumns) {
                                        if (col != null) {
                                            val sv = colSuperVolField?.get(col) ?: col.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.also { cachedVolumeColumnField = it }.get(col)
                                            if (sv === textView) {
                                                val colStreamField = cachedColumnStreamField ?: runCatching {
                                                    col.javaClass.getDeclaredField("stream").apply { isAccessible = true }.also { cachedColumnStreamField = it }
                                                }.getOrNull()
                                                val colStreamGetter = if (colStreamField == null && cachedColumnStreamGetter == null) {
                                                    runCatching { col.javaClass.getMethod("getStream").also { cachedColumnStreamGetter = it } }.getOrNull()
                                                } else cachedColumnStreamGetter
                                                foundStream = if (colStreamField != null) {
                                                    runCatching { colStreamField.get(col) as Int }.getOrDefault(-1)
                                                } else if (colStreamGetter != null) {
                                                    runCatching { colStreamGetter.invoke(col) as Int }.getOrDefault(-1)
                                                } else -1
                                                break
                                            }
                                        }
                                    }
                                }

                                if (foundStream >= 0) {
                                    val states = ssField_states?.get(mState) ?: return@runCatching false
                                    val streamState = ssMethod_get?.invoke(states, foundStream) ?: return@runCatching false
                                    val level = (ssField_level?.get(streamState) as? Int) ?: 0
                                    val levelMax = (ssField_levelMax?.get(streamState) as? Int) ?: 0
                                    val pct = if (levelMax > 0) Math.round(level * 1f / levelMax * 100f).coerceIn(0, 100) else 0

                                    textView.text = "$pct%"

                                    val mExpanded = vpcField_mExpanded?.get(thisObject) as Boolean
                                    if (textView === mSuperVolume) {
                                        textView.visibility = if (mExpanded) View.GONE else View.VISIBLE
                                    } else {
                                        textView.visibility = if (mExpanded) View.VISIBLE else View.INVISIBLE
                                    }

                                    textView.typeface = Typeface.DEFAULT_BOLD
                                    val sameStyleSuper = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                                    if (sameStyleSuper) {
                                        putTag(textView, "sliderType", "VolumePanelViewController")
                                        val activeColor = SliderHookHelper.getActiveColor(textView.context, "VolumePanelViewController")
                                        ColorOverrideLock.isSettingColor.set(true)
                                        runCatching {
                                            textView.setTextColor(activeColor)
                                        }
                                        ColorOverrideLock.isSettingColor.set(false)
                                    } else {
                                        val context = textView.context
                                        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                                        val textColor = if (isDark) android.graphics.Color.parseColor("#B3FFFFFF") else android.graphics.Color.parseColor("#B3000000")
                                        textView.setTextColor(textColor)
                                    }
                                    true
                                } else {
                                    false
                                }
                            }.getOrDefault(false)
                            if (skipped) {
                                return@intercept null
                            }
                        }
                    }
                    chain.proceed()
                }
            }
        }

        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "updateSuperVolumeViewColor" }?.let { method ->
            method.hook {
                intercept { chain ->
                    applyBadgeThemeColors(chain.thisObject)
                    null
                }
            }
        }

        // ─── MiuiVolumeDialogView ──────────────────────────────────────────────
        val clzVolumeDialogView = parent.resolveClass("com.android.systemui.miui.volume.MiuiVolumeDialogView")
        clzVolumeDialogView?.declaredMethods?.firstOrNull {
            it.name == "updateSuperVolumeVisibility" &&
            it.parameterTypes.size == 1 &&
            it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }?.let { method ->
            method.hook {
                before { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        val view = param.thisObject
                        val isExpanded = runCatching {
                            view.javaClass.getMethod("isExpanded").invoke(view) as Boolean
                        }.getOrDefault(false)
                        val inCCMainPage = runCatching {
                            view.javaClass.getMethod("inCCMainPage").invoke(view) as Boolean
                        }.getOrNull()
                        if (inCCMainPage != true) {
                            param.args[0] = !isExpanded
                        }
                    }
                }
            }
        }
    }
}
