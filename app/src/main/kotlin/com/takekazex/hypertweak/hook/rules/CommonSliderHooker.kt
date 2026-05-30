package com.takekazex.hypertweak.hook.rules

import android.util.Log
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.applyTopTextStyle
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.brightnessActiveBlendToken
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.brightnessColor
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.brightnessBlendToken
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.brightnessInactiveBlendToken
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.getTag
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.getTopTextFromHolder
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.isBlurSupported
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.putTag
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.removeTag
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.volumeActiveBlendToken
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.volumeColor
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.volumeBlendToken
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.volumeInactiveBlendToken

class CommonSliderHooker(
    private val parent: SliderPercentageHooker
) : DynamicHooker() {

    override fun onHook() {
        val clzViewHolder = parent.resolveClass("miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder")
        Log.d("HyperTweak", "CommonSliderHooker onHook - clzViewHolder: ${clzViewHolder?.name}")

        clzViewHolder?.let { clz ->
            val updateBlendBlurMethod = clz.declaredMethods.firstOrNull { it.name == "updateBlendBlur" }
                ?: clz.superclass?.declaredMethods?.firstOrNull { it.name == "updateBlendBlur" }
            updateBlendBlurMethod?.let { method ->
                Log.d("HyperTweak", "Hooking ToggleSliderViewHolder updateBlendBlur")
                method.hook {
                    after { param ->
                        if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                            runCatching {
                                val topText = getTopTextFromHolder(param.thisObject) ?: return@runCatching
                                applyTopTextStyle(topText)
                            }
                        }
                    }
                }
            }
        }

        // ─── AnimateColorView Hooks ────────────────────────────────────────────
        val clzAnimateColorView = parent.resolveClass("miui.systemui.controlcenter.widget.AnimateColorView")
        Log.d("HyperTweak", "CommonSliderHooker onHook - clzAnimateColorView: ${clzAnimateColorView?.name}")

        clzAnimateColorView?.declaredMethods?.firstOrNull { it.name == "recycle" }?.let { method ->
            Log.d("HyperTweak", "Hooking AnimateColorView recycle")
            method.hook {
                before { param ->
                    val view = param.thisObject as View
                    removeTag(view, "topText")
                    removeTag(view, "sliderType")
                    val anim = removeTag(view, "topTextAnimator") as? android.animation.ValueAnimator
                    anim?.cancel()
                }
            }
        }

        clzAnimateColorView?.declaredMethods?.firstOrNull {
            it.name == "updateIconColor" && it.parameterTypes.size == 5
        }?.let { method ->
            Log.d("HyperTweak", "Hooking AnimateColorView updateIconColor")
            method.hook {
                before { param ->
                    if (Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
                        val view = param.thisObject as View
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

                        if (topText != null && sliderType != null) {
                            val fromToken = param.args[0]
                            val toToken = param.args[1]
                            val fromColorResId = param.args[2] as Int
                            val toColorResId = param.args[3] as Int
                            val animate = param.args[4] as Boolean

                            val resolvedFromColor = runCatching { view.context.getColor(fromColorResId) }.getOrDefault(0xFFFFFFFF.toInt())
                            val resolvedToColor = runCatching { view.context.getColor(toColorResId) }.getOrDefault(0xFFFFFFFF.toInt())

                            val sameStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                            
                            // If sameStyle is false, the volume topText uses plain text and should not be blended
                            if (sameStyle || sliderType != "VolumeSliderController") {
                                val fromHsv = FloatArray(3)
                                val toHsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(resolvedFromColor, fromHsv)
                                android.graphics.Color.colorToHSV(resolvedToColor, toHsv)
                                
                                if (sliderType == "BrightnessSliderController") {
                                    brightnessColor = resolvedToColor
                                    brightnessBlendToken = toToken
                                    if (toHsv[1] > 0.2f) {
                                        brightnessActiveBlendToken = toToken
                                    } else {
                                        brightnessInactiveBlendToken = toToken
                                    }
                                    if (fromHsv[1] > 0.2f) {
                                        brightnessActiveBlendToken = fromToken
                                    }
                                } else if (sliderType == "VolumeSliderController") {
                                    volumeColor = resolvedToColor
                                    volumeBlendToken = toToken
                                    if (toHsv[1] > 0.2f) {
                                        volumeActiveBlendToken = toToken
                                    } else {
                                        volumeInactiveBlendToken = toToken
                                    }
                                    if (fromHsv[1] > 0.2f) {
                                        volumeActiveBlendToken = fromToken
                                    }
                                }

                                (getTag(view, "topTextAnimator") as? android.animation.ValueAnimator)?.cancel()

                                runCatching {
                                    if (sameStyle && isBlurSupported(view.context)) {
                                        if (sliderType == "BrightnessSliderController") {
                                            applyTopTextStyle(topText)
                                            return@runCatching
                                        }

                                        topText.setMiViewBlurMode(3)
                                        
                                        val pct = runCatching {
                                            topText.text.toString().removeSuffix("%").toInt()
                                        }.getOrDefault(0)

                                        val targetFromToken = if (pct >= 50) volumeInactiveBlendToken else fromToken
                                        val targetToToken = if (pct >= 50) volumeInactiveBlendToken else toToken

                                        val clzMiBlurCompat = topText.context.classLoader.loadClass("miui.systemui.util.MiBlurCompat")
                                        val clzColorBlendToken = topText.context.classLoader.loadClass("miuix.theme.token.ColorBlendToken")

                                        if (animate && targetFromToken != null && targetToToken != null) {
                                            val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                                                duration = 200
                                                addUpdateListener { anim ->
                                                     val fraction = anim.animatedValue as Float
                                                     runCatching {
                                                         clzMiBlurCompat.getMethod("setMiBackgroundBlendColors", View::class.java, clzColorBlendToken, clzColorBlendToken, Float::class.javaPrimitiveType)
                                                            .invoke(null, topText, targetFromToken, targetToToken, fraction)
                                                     }
                                                 }
                                             }
                                             putTag(view, "topTextAnimator", animator)
                                             animator.start()
                                         } else {
                                             val tokenToUse = targetToToken ?: toToken
                                             if (tokenToUse != null) {
                                                 clzMiBlurCompat.getMethod("setMiBackgroundBlendColors", View::class.java, clzColorBlendToken)
                                                    .invoke(null, topText, tokenToUse)
                                             } else {
                                                 applyTopTextStyle(topText)
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
                            }
                        }
                    }
                }
            }
        }
    }
}
