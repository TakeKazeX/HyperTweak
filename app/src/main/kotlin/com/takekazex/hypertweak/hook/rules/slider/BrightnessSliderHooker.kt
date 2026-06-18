package com.takekazex.hypertweak.hook.rules.slider
 
import android.util.Log
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.applyTopTextStyle
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.findHolder
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.formatPercent
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.fromHeight
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.fromLeft
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.fromTop
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.fromWidth
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.getTopTextFromHolder
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.initTopText
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.isBlurSupported
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.putTag
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.toHeight
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.toLeft
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.toTop
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.toWidth
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.updatePercentageText

class BrightnessSliderHooker(
    private val parent: SliderPercentageHooker
) : DynamicHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE


    // Cached fields for BrightnessPanelAnimator (frameCallback hot path)
    private var animatorSizeField: java.lang.reflect.Field? = null
    private var animatorGetToViewMethod: java.lang.reflect.Method? = null
    private var animatorFromViewField: java.lang.reflect.Field? = null
    private var viewGetTopTextMethod: java.lang.reflect.Method? = null
    private var viewGetSliderBindingMethod: java.lang.reflect.Method? = null
    private var bindingTopTextField: java.lang.reflect.Field? = null

    // Cached fields for BrightnessSliderController (setInMirror)
    private var inMirrorField: java.lang.reflect.Field? = null
    private var mirrorBlurProviderField: java.lang.reflect.Field? = null

    // Cached fields for BrightnessPanelSliderDelegate (prepareShow / updateIconProgress)
    private var delegateBindingField: java.lang.reflect.Field? = null
    private var delegateToggleSliderField: java.lang.reflect.Field? = null
    private var delegateTopTextField: java.lang.reflect.Field? = null
    private var delegateSliderField: java.lang.reflect.Field? = null
    private var delegateUpdateProgressMethod: java.lang.reflect.Method? = null

    private fun getOrCacheAnimatorSizeField(clazz: Class<*>): java.lang.reflect.Field? {
        animatorSizeField?.let { return it }
        val f = clazz.getDeclaredField("size").apply { isAccessible = true }
        animatorSizeField = f
        return f
    }

    private fun getOrCacheAnimatorGetToViewMethod(clazz: Class<*>): java.lang.reflect.Method? {
        animatorGetToViewMethod?.let { return it }
        val m = clazz.getDeclaredMethod("getToView").apply { isAccessible = true }
        animatorGetToViewMethod = m
        return m
    }

    private fun getOrCacheAnimatorFromViewField(clazz: Class<*>): java.lang.reflect.Field? {
        animatorFromViewField?.let { return it }
        val f = clazz.getDeclaredField("fromView").apply { isAccessible = true }
        animatorFromViewField = f
        return f
    }

    private fun getOrCacheViewGetTopTextMethod(clazz: Class<*>): java.lang.reflect.Method? {
        viewGetTopTextMethod?.let { return it }
        val m = clazz.getMethod("getTopText")
        viewGetTopTextMethod = m
        return m
    }

    private fun getOrCacheViewGetSliderBindingMethod(clazz: Class<*>): java.lang.reflect.Method? {
        viewGetSliderBindingMethod?.let { return it }
        val m = clazz.getMethod("getSliderBinding")
        viewGetSliderBindingMethod = m
        return m
    }

    private fun getOrCacheBindingTopTextField(clazz: Class<*>): java.lang.reflect.Field? {
        bindingTopTextField?.let { return it }
        val f = clazz.getField("topText")
        bindingTopTextField = f
        return f
    }

    private fun getOrCacheInMirrorField(clazz: Class<*>): java.lang.reflect.Field? {
        inMirrorField?.let { return it }
        val f = clazz.getDeclaredField("inMirror").apply { isAccessible = true }
        inMirrorField = f
        return f
    }

    private fun getOrCacheMirrorBlurProviderField(clazz: Class<*>): java.lang.reflect.Field? {
        mirrorBlurProviderField?.let { return it }
        val f = runCatching { clazz.getDeclaredField("mirrorBlurProvider").apply { isAccessible = true } }.getOrNull()
        mirrorBlurProviderField = f
        return f
    }

    private fun getOrCacheDelegateBindingField(clazz: Class<*>): java.lang.reflect.Field? {
        delegateBindingField?.let { return it }
        val f = clazz.getDeclaredField("binding").apply { isAccessible = true }
        delegateBindingField = f
        return f
    }

    private fun getOrCacheDelegateToggleSliderField(clazz: Class<*>): java.lang.reflect.Field? {
        delegateToggleSliderField?.let { return it }
        val f = clazz.getField("toggleSlider").apply { isAccessible = true }
        delegateToggleSliderField = f
        return f
    }

    private fun getOrCacheDelegateTopTextField(clazz: Class<*>): java.lang.reflect.Field? {
        delegateTopTextField?.let { return it }
        val f = clazz.getField("topText").apply { isAccessible = true }
        delegateTopTextField = f
        return f
    }

    private fun getOrCacheDelegateSliderField(clazz: Class<*>): java.lang.reflect.Field? {
        delegateSliderField?.let { return it }
        val f = clazz.getField("slider").apply { isAccessible = true }
        delegateSliderField = f
        return f
    }

    private fun getOrCacheDelegateUpdateProgressMethod(clazz: Class<*>): java.lang.reflect.Method? {
        delegateUpdateProgressMethod?.let { return it }
        val m = clazz.getDeclaredMethod("updateIconProgress", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        delegateUpdateProgressMethod = m
        return m
    }

    override fun onHook() {
        val clzBrightnessSlider = parent.resolveClass("miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController")

        clzBrightnessSlider?.declaredMethods?.firstOrNull { it.name == "onBindViewHolder" }?.let { method ->
            method.hook {
                after { param ->
                    if (parent.showPercentageEnabled) {
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
                    if (parent.showPercentageEnabled) {
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
                        getOrCacheInMirrorField(thisObject.javaClass)?.get(thisObject) as? Boolean
                    }.getOrDefault(false) ?: false

                    if (inMirrorArg == currentInMirror) {
                        return@intercept chain.proceed()
                    }

                    val result = chain.proceed()

                    runCatching {
                        val holder = thisObject.javaClass.getMethod("getHolder").invoke(thisObject) ?: return@runCatching
                        val binding = runCatching { holder.javaClass.getMethod("getBinding").invoke(holder) }.getOrNull() ?: holder
                        val mirrorBlurProvider = getOrCacheMirrorBlurProviderField(binding.javaClass)?.let { f ->
                            runCatching { f.get(binding) as? View }.getOrNull()
                        }
                        val topText = runCatching { getOrCacheBindingTopTextField(binding.javaClass)?.get(binding) as? TextView }.getOrNull()
                            ?: runCatching { getOrCacheViewGetTopTextMethod(binding.javaClass)?.invoke(binding) as? TextView }.getOrNull()
                            ?: return@runCatching

                        val sameStyle = parent.sameStyleEnabled
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
                        val fromView = getOrCacheAnimatorFromViewField(thisObject.javaClass)?.get(thisObject) ?: return@runCatching
                        val fromText = getOrCacheViewGetTopTextMethod(fromView.javaClass)?.invoke(fromView) as? TextView ?: return@runCatching
                        fromLeft = fromText.left
                        fromTop = fromText.top
                        fromWidth = fromText.width
                        fromHeight = fromText.height

                        val getToView = getOrCacheAnimatorGetToViewMethod(thisObject.javaClass) ?: return@runCatching
                        val toView = getToView.invoke(thisObject) ?: return@runCatching
                        val sliderBinding = getOrCacheViewGetSliderBindingMethod(toView.javaClass)?.invoke(toView) ?: return@runCatching
                        val toText = getOrCacheBindingTopTextField(sliderBinding.javaClass)?.get(sliderBinding) as? TextView ?: return@runCatching
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
                        val fraction = getOrCacheAnimatorSizeField(thisObject.javaClass)?.get(thisObject) as? Float ?: return@runCatching
                        val left = fromLeft + (toLeft - fromLeft) * fraction
                        val top = fromTop + (toTop - fromTop) * fraction
                        val width = fromWidth + (toWidth - fromWidth) * fraction
                        val height = fromHeight + (toHeight - fromHeight) * fraction

                        val getToView = getOrCacheAnimatorGetToViewMethod(thisObject.javaClass) ?: return@runCatching
                        val toView = getToView.invoke(thisObject) ?: return@runCatching
                        val sliderBinding = getOrCacheViewGetSliderBindingMethod(toView.javaClass)?.invoke(toView) ?: return@runCatching
                        val topText = getOrCacheBindingTopTextField(sliderBinding.javaClass)?.get(sliderBinding) as? TextView ?: return@runCatching
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
                    if (parent.showPercentageEnabled) {
                        runCatching {
                            val thisObject = param.thisObject
                            val binding = getOrCacheDelegateBindingField(thisObject.javaClass)?.get(thisObject) ?: return@runCatching
                            val toggleSlider = getOrCacheDelegateToggleSliderField(binding.javaClass)?.get(binding) ?: return@runCatching
                            val topText = getOrCacheDelegateTopTextField(toggleSlider.javaClass)?.get(toggleSlider) as? TextView ?: return@runCatching

                            initTopText(topText)
                            putTag(topText, "sliderType", "BrightnessSliderController")
                            applyTopTextStyle(topText, force = true)

                            getOrCacheDelegateUpdateProgressMethod(thisObject.javaClass)?.invoke(thisObject, true)
                        }.onFailure { t ->
                            Log.e("HyperTweak", "Error in BrightnessPanelSliderDelegate.prepareShow hook", t)
                        }
                    }
                }
            }
        }

        clzBrightnessPanelDelegate?.declaredMethods?.filter { it.name == "updateIconProgress" }?.forEach { method ->
            method.hook {
                after { param ->
                    if (parent.showPercentageEnabled) {
                        runCatching {
                            val thisObject = param.thisObject
                            val binding = getOrCacheDelegateBindingField(thisObject.javaClass)?.get(thisObject) ?: return@runCatching
                            val toggleSlider = getOrCacheDelegateToggleSliderField(binding.javaClass)?.get(binding) ?: return@runCatching
                            val topText = getOrCacheDelegateTopTextField(toggleSlider.javaClass)?.get(toggleSlider) as? TextView ?: return@runCatching
                            val slider = getOrCacheDelegateSliderField(toggleSlider.javaClass)?.get(toggleSlider) as? android.widget.SeekBar ?: return@runCatching

                            val level = slider.progress
                            val maxLevel = slider.max
                            val pct = if (maxLevel > 0) Math.round(level * 1f / maxLevel * 100f).coerceIn(0, 100) else 0
                            topText.text = formatPercent(pct)
                            applyTopTextStyle(topText, sliderType = "BrightnessSliderController")
                        }.onFailure { t ->
                            Log.e("HyperTweak", "Error updating BrightnessPanelSliderDelegate percentage", t)
                        }
                    }
                }
            }
        }
    }
}
