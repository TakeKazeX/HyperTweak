package com.takekazex.hypertweak.hook.rules

import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.applyTopTextStyle
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.getTag
import com.takekazex.hypertweak.hook.rules.SliderHookHelper.getTopTextFromHolder

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
                                val sliderType = getTag(topText, "sliderType") as? String
                                applyTopTextStyle(topText, force = true, sliderType = sliderType)
                            }
                        }
                    }
                }
            }
        }
    }
}
