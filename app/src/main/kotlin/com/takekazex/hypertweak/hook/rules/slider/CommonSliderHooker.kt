package com.takekazex.hypertweak.hook.rules.slider

import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.applyTopTextStyle
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.getTag
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.getTopTextFromHolder

class CommonSliderHooker(
    private val parent: SliderSession
) : DynamicHooker() {

    override fun onHook() {
        val clzViewHolder = parent.resolveClass("miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder")

        clzViewHolder?.let { clz ->
            val updateBlendBlurMethod = clz.declaredMethods.firstOrNull { it.name == "updateBlendBlur" }
                ?: clz.superclass?.declaredMethods?.firstOrNull { it.name == "updateBlendBlur" }
            updateBlendBlurMethod?.let { method ->
                method.hook {
                    after { param ->
                        if (parent.policy.display != 0) {
                            runCatching {
                                val topText = getTopTextFromHolder(param.thisObject) ?: return@runCatching
                                val sliderType = getTag(topText, "sliderType") as? String ?: return@runCatching
                                if (parent.policy.controlCenter(sliderType.contains("Brightness"))) {
                                    applyTopTextStyle(topText, force = true, sliderType = sliderType)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
