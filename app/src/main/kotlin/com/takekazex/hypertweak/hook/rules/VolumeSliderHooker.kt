package com.takekazex.hypertweak.hook.rules

import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.applyTopTextStyle
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.blendColors
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.calcVolumePercent
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.calcVolumePercentFromSliderValue
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.findHolder
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.getTag
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.getTopTextFromHolder
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.initTopText
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.putTag
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.updatePercentageText

class VolumeSliderHooker(
    private val parent: SliderPercentageHooker
) : DynamicHooker() {

    override fun onHook() {
        val clzVolumeSlider = parent.resolveClass("miui.systemui.controlcenter.panel.main.volume.VolumeSliderController")
        Log.d("HyperTweak", "VolumeSliderHooker onHook - clzVolumeSlider: ${clzVolumeSlider?.name}")

        clzVolumeSlider?.declaredMethods?.firstOrNull { it.name == "onBindViewHolder" }?.let { method ->
            Log.d("HyperTweak", "Hooking Volume onBindViewHolder")
            method.hook {
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        val sameStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                        runCatching {
                            val holder = findHolder(param.thisObject) ?: return@runCatching
                            val topText = getTopTextFromHolder(holder) ?: return@runCatching
                            if (sameStyle) {
                                initTopText(topText)
                                putTag(topText, "sliderType", "VolumeSliderController")
                                applyTopTextStyle(topText, force = true, sliderType = "VolumeSliderController")
                                updatePercentageText(param.thisObject, "VolumeSliderController")
                            } else {
                                topText.post { topText.visibility = View.GONE }
                            }
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
                                val sameStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                                if (sameStyle) {
                                    applyTopTextStyle(topText, sliderType = "VolumeSliderController")
                                }
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
                                val sameStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                                if (sameStyle) {
                                    applyTopTextStyle(topText, sliderType = "VolumeSliderController")
                                }
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
                        val volumeColumn = thisObject.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(thisObject)
                        val superVolume = volumeColumn.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.get(volumeColumn) as TextView

                        val blendedColor = blendColors(fromColorList.defaultColor, toColorList.defaultColor, fraction)
                        superVolume.setTextColor(android.content.res.ColorStateList.valueOf(blendedColor))
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
                    val showPct = Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)
                    val sameStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                    if (showPct) {
                        val isExpanded = param.args[5] as? Boolean ?: false
                        val thisObject = param.thisObject
                        val isInCCMainPage = runCatching {
                            thisObject.javaClass.getMethod("isInCCMainPage").invoke(thisObject) as Boolean
                        }.getOrDefault(true)
                        
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
                            runCatching {
                                val superVolume = thisObject.javaClass.getDeclaredField("superVolume")
                                    .apply { isAccessible = true }.get(thisObject) as? TextView ?: return@runCatching
                                superVolume.post { superVolume.visibility = View.GONE }
                            }
                        }
                    }
                }
            }
        }

        // ─── VolumePanelViewController ─────────────────────────────────────────
        val clzVolumeViewController = parent.resolveClass("com.android.systemui.miui.volume.VolumePanelViewController")
        
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
                            val mState = thisObject.javaClass.getDeclaredField("mState").apply { isAccessible = true }.get(thisObject) ?: return@runCatching
                            val mExpanded = thisObject.javaClass.getDeclaredField("mExpanded").apply { isAccessible = true }.get(thisObject) as Boolean
                            val activeStream = thisObject.javaClass.getDeclaredField("mActiveStream").apply { isAccessible = true }.get(thisObject) as Int
                            val column = param.args[0] ?: return@runCatching
                            
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

                                val sameStyleVolume = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                                val columnSuperVolume = column.javaClass.getDeclaredField("superVolume").apply { isAccessible = true }.get(column) as? TextView
                                if (columnSuperVolume != null) {
                                    columnSuperVolume.text = "$pct%"
                                    columnSuperVolume.visibility = if (mExpanded) View.VISIBLE else View.GONE
                                    if (mExpanded) {
                                        columnSuperVolume.typeface = Typeface.DEFAULT_BOLD
                                        if (sameStyleVolume) {
                                            applyTopTextStyle(columnSuperVolume, sliderType = "VolumePanelViewController")
                                        }
                                    }
                                }

                                if (!mExpanded) {
                                    val isNeedShowDialog = thisObject.javaClass.getDeclaredField("mNeedShowDialog").apply { isAccessible = true }.get(thisObject) as Boolean
                                    if (isNeedShowDialog && stream == activeStream) {
                                        val mSuperVolume = thisObject.javaClass.getDeclaredField("mSuperVolume").apply { isAccessible = true }.get(thisObject) as? TextView
                                        if (mSuperVolume != null) {
                                            mSuperVolume.text = "$pct%"
                                            mSuperVolume.typeface = Typeface.DEFAULT_BOLD
                                            if (sameStyleVolume) {
                                                applyTopTextStyle(mSuperVolume, sliderType = "VolumePanelViewController")
                                            }
                                        }
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

        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "updateSuperVolumeText" }?.let { method ->
            method.hook {
                intercept { chain ->
                    val param = chain.args
                    val thisObject = chain.thisObject
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        val textView = param[0] as? TextView
                        if (textView != null) {
                            val skipped = runCatching {
                                val mState = thisObject.javaClass.getDeclaredField("mState").apply { isAccessible = true }.get(thisObject) ?: return@runCatching false
                                val mColumns = thisObject.javaClass.getDeclaredField("mColumns").apply { isAccessible = true }.get(thisObject) as List<*>
                                
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
                                    val streamState = getMethod.invoke(states, foundStream) ?: return@runCatching false
                                    val level = streamState.javaClass.getDeclaredField("level").apply { isAccessible = true }.get(streamState) as Int
                                    val levelMax = streamState.javaClass.getDeclaredField("levelMax").apply { isAccessible = true }.get(streamState) as Int
                                    val pct = if (levelMax > 0) Math.round(level * 1f / levelMax * 100f).coerceIn(0, 100) else 0

                                    textView.text = "$pct%"
                                    textView.visibility = View.VISIBLE
                                    textView.typeface = Typeface.DEFAULT_BOLD
                                    val sameStyleSuper = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                                    if (sameStyleSuper) {
                                        applyTopTextStyle(textView, sliderType = "VolumePanelViewController")
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
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        runCatching {
                            val thisObject = param.thisObject
                            val mSuperVolume = thisObject.javaClass.getDeclaredField("mSuperVolume").apply { isAccessible = true }.get(thisObject) as? TextView ?: return@runCatching
                            mSuperVolume.typeface = Typeface.DEFAULT_BOLD
                        }
                    }
                }
            }
        }

        clzVolumeViewController?.declaredMethods?.firstOrNull { it.name == "updateSuperVolumeView" }?.let { method ->
            method.hook {
                before { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        runCatching {
                            val thisObject = param.thisObject
                            val mExpanded = thisObject.javaClass.getDeclaredField("mExpanded").apply { isAccessible = true }.get(thisObject) as Boolean
                            val mSuperVolumeBg = thisObject.javaClass.getDeclaredField("mSuperVolumeBg").apply { isAccessible = true }.get(thisObject) as? View

                            mSuperVolumeBg?.visibility = if (mExpanded) View.GONE else View.VISIBLE
                        }.onFailure { t ->
                            Log.e("HyperTweak", "Error in VolumePanelViewController.updateSuperVolumeView BEFORE hook", t)
                        }
                    }
                }
            }
        }
    }
}
