package com.takekazex.hypertweak.hook.rules

import android.util.Log
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.applyTopTextStyle
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.findHolder
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.fromHeight
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.fromLeft
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.fromTop
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.fromWidth
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.getTopTextFromHolder
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.initTopText
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.isBlurSupported
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.putTag
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.toHeight
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.toLeft
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.toTop
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.toWidth
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.updatePercentageText

class BrightnessSliderHooker(
    private val parent: SliderPercentageHooker
) : DynamicHooker() {

    override fun onHook() {
        val clzBrightnessSlider = parent.resolveClass("miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController")
        Log.d("HyperTweak", "BrightnessSliderHooker onHook - clzBrightnessSlider: ${clzBrightnessSlider?.name}")

        clzBrightnessSlider?.declaredMethods?.firstOrNull { it.name == "onBindViewHolder" }?.let { method ->
            Log.d("HyperTweak", "Hooking Brightness onBindViewHolder")
            method.hook {
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        runCatching {
                            val holder = findHolder(param.thisObject) ?: return@runCatching
                            val topText = getTopTextFromHolder(holder) ?: return@runCatching
                            initTopText(topText)
                            putTag(topText, "sliderType", "BrightnessSliderController")
                            applyTopTextStyle(topText, force = true)
                            updatePercentageText(param.thisObject, "BrightnessSliderController")
                        }
                    }
                }
            }
        }

        clzBrightnessSlider?.declaredMethods?.firstOrNull { it.name == "updateIconProgress" }?.let { method ->
            method.hook {
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        updatePercentageText(param.thisObject, "BrightnessSliderController")
                    }
                }
            }
        }

        clzBrightnessSlider?.declaredMethods?.firstOrNull {
            it.name == "setInMirror" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }?.let { method ->
            method.hook {
                intercept { chain ->
                    val param = chain.args
                    val inMirrorArg = param[0] as Boolean
                    val thisObject = chain.thisObject
                    val currentInMirror = runCatching {
                        thisObject.javaClass.getDeclaredField("inMirror").apply { isAccessible = true }.get(thisObject) as Boolean
                    }.getOrDefault(false)

                    if (inMirrorArg == currentInMirror) {
                        return@intercept chain.proceed()
                    }

                    val result = chain.proceed()

                    runCatching {
                        val holder = thisObject.javaClass.getMethod("getHolder").invoke(thisObject) ?: return@runCatching
                        val binding = runCatching { holder.javaClass.getMethod("getBinding").invoke(holder) }.getOrNull() ?: holder
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
                            topText.setMiViewBlurMode(3)
                        } else {
                            topText.clearMiBlur()
                        }
                        applyTopTextStyle(topText, force = true)
                    }.onFailure { t ->
                        Log.e("HyperTweak", "Error in BrightnessSliderController.setInMirror hook", t)
                    }
                    result
                }
            }
        }

        // ─── Brightness Panel Animator Hooks (Transition Positions) ───────────
        val clzBrightnessAnimator = parent.resolveClass("miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelAnimator")
        clzBrightnessAnimator?.declaredMethods?.firstOrNull { it.name == "calculateViewValues" }?.let { method ->
            method.hook {
                after { param ->
                    runCatching {
                        val thisObject = param.thisObject
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
                }
            }
        }

        clzBrightnessAnimator?.declaredMethods?.firstOrNull { it.name == "frameCallback" }?.let { method ->
            method.hook {
                after { param ->
                    runCatching {
                        val thisObject = param.thisObject
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
                }
            }
        }

        // ─── BrightnessPanelSliderDelegate (Expanded Brightness) ────────────────
        val clzBrightnessPanelDelegate = parent.resolveClass("miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate")
        clzBrightnessPanelDelegate?.declaredMethods?.firstOrNull { it.name == "prepareShow" }?.let { method ->
            method.hook {
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        runCatching {
                            val thisObject = param.thisObject
                            val binding = thisObject.javaClass.getDeclaredField("binding").apply { isAccessible = true }.get(thisObject)
                            val toggleSlider = binding.javaClass.getField("toggleSlider").apply { isAccessible = true }.get(binding)
                            val topText = toggleSlider.javaClass.getField("topText").apply { isAccessible = true }.get(toggleSlider) as TextView
                            
                            initTopText(topText)
                            putTag(topText, "sliderType", "BrightnessSliderController")
                            applyTopTextStyle(topText, force = true)
                            
                            thisObject.javaClass.getDeclaredMethod("updateIconProgress", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(thisObject, true)
                        }.onFailure { t ->
                            Log.e("HyperTweak", "Error in BrightnessPanelSliderDelegate.prepareShow hook", t)
                        }
                    }
                }
            }
        }

        clzBrightnessPanelDelegate?.declaredMethods?.firstOrNull { it.name == "updateIconProgress" }?.let { method ->
            method.hook {
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        runCatching {
                            val thisObject = param.thisObject
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
                }
            }
        }
    }
}
