package com.takekazex.hypertweak.hook.rules.slider

import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.applyTopTextStyle
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.getTag
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.getTopTextFromHolder

class CommonSliderHooker(
    private val parent: SliderPercentageHooker
) : DynamicHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE


    override fun onHook() {
        val clzViewHolder = parent.resolveClass("miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder")

        clzViewHolder?.let { clz ->
            val updateBlendBlurMethod = clz.declaredMethods.firstOrNull { it.name == "updateBlendBlur" }
                ?: clz.superclass?.declaredMethods?.firstOrNull { it.name == "updateBlendBlur" }
            updateBlendBlurMethod?.let { method ->
                method.hook {
                    after { param ->
                        if (parent.showPercentageEnabled) {
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
