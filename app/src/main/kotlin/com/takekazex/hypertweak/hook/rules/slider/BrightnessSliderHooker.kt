package com.takekazex.hypertweak.hook.rules.slider
 
import com.takekazex.hypertweak.hook.base.HostAccess
import android.util.Log
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.applyTopTextStyle
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.findHolder
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.formatPercent
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.getTopTextFromHolder
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.initTopText
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.refreshViewCache
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.isBlurSupported
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.putTag
import com.takekazex.hypertweak.hook.rules.slider.SliderHookHelper.updatePercentageText

class BrightnessSliderHooker(
    private val parent: SliderSession
) : DynamicHooker() {

    private data class AnimatorBounds(
        val fromLeft: Int, val fromTop: Int, val fromWidth: Int, val fromHeight: Int,
        val toLeft: Int, val toTop: Int, val toWidth: Int, val toHeight: Int
    )
    private val animatorBounds = generationMainResource(java.util.WeakHashMap<Any, AnimatorBounds>()) { it.clear() }

    private fun getOrCacheAnimatorSizeField(clazz: Class<*>): java.lang.reflect.Field? =
        HostAccess.requireDeclaredField(clazz, "size", accessible = true)

    private fun getOrCacheAnimatorGetToViewMethod(clazz: Class<*>): java.lang.reflect.Method? =
        HostAccess.requireDeclaredMethod(clazz, "getToView", accessible = true)

    private fun getOrCacheAnimatorFromViewField(clazz: Class<*>): java.lang.reflect.Field? =
        HostAccess.requireDeclaredField(clazz, "fromView", accessible = true)

    private fun getOrCacheViewGetTopTextMethod(clazz: Class<*>): java.lang.reflect.Method? =
        HostAccess.requirePublicMethod(clazz, "getTopText", accessible = false)

    private fun getOrCacheViewGetSliderBindingMethod(clazz: Class<*>): java.lang.reflect.Method? =
        HostAccess.requirePublicMethod(clazz, "getSliderBinding", accessible = false)

    private fun getOrCacheBindingTopTextField(clazz: Class<*>): java.lang.reflect.Field? =
        HostAccess.requirePublicField(clazz, "topText", accessible = false)

    private fun getOrCacheInMirrorField(clazz: Class<*>): java.lang.reflect.Field? =
        HostAccess.requireDeclaredField(clazz, "inMirror", accessible = true)

    private fun getOrCacheMirrorBlurProviderField(clazz: Class<*>): java.lang.reflect.Field? =
        runCatching { HostAccess.requireDeclaredField(clazz, "mirrorBlurProvider", accessible = true) }.getOrNull()

    private fun getOrCacheDelegateBindingField(clazz: Class<*>): java.lang.reflect.Field? =
        HostAccess.requireDeclaredField(clazz, "binding", accessible = true)

    private fun getOrCacheDelegateToggleSliderField(clazz: Class<*>): java.lang.reflect.Field? =
        HostAccess.requirePublicField(clazz, "toggleSlider", accessible = true)

    private fun getOrCacheDelegateTopTextField(clazz: Class<*>): java.lang.reflect.Field? =
        HostAccess.requirePublicField(clazz, "topText", accessible = true)

    private fun getOrCacheDelegateSliderField(clazz: Class<*>): java.lang.reflect.Field? =
        HostAccess.requirePublicField(clazz, "slider", accessible = true)

    private fun getOrCacheDelegateUpdateProgressMethod(clazz: Class<*>): java.lang.reflect.Method? =
        HostAccess.requireDeclaredMethod(clazz, "updateIconProgress", Boolean::class.javaPrimitiveType, accessible = true)

    override fun onHook() {
        val clzBrightnessSlider = parent.resolveClass("miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController")

        clzBrightnessSlider?.declaredMethods?.firstOrNull { it.name == "onUnbindViewHolder" }?.let { method ->
            method.hook { before { param -> SliderHookHelper.releaseSlider(param.thisObject) } }
        }

        clzBrightnessSlider?.declaredMethods?.firstOrNull { it.name == "onBindViewHolder" }?.let { method ->
            method.hook {
                after { param ->
                    runCatching {
                        val holder = findHolder(param.thisObject) ?: return@runCatching
                        val text = getTopTextFromHolder(holder) ?: return@runCatching
                        refreshViewCache(param.thisObject, holder, text)
                    }
                    if (parent.policy.controlCenter(true)) {
                        runCatching {
                            val holder = findHolder(param.thisObject) ?: return@runCatching
                            val topText = getTopTextFromHolder(holder) ?: return@runCatching
                            // A recycled holder means the cached views are stale — repoint the cache at
                            // the freshly bound views so percentage updates don't strand on detached ones.
                            refreshViewCache(param.thisObject, holder, topText)
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
                    if (parent.policy.controlCenter(true)) {
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
                    if (!parent.policy.controlCenter(true)) return@intercept result

                    runCatching {
                        val holder = HostAccess.requirePublicMethod(thisObject.javaClass, "getHolder", accessible = false).invoke(thisObject) ?: return@runCatching
                        val binding = runCatching { HostAccess.requirePublicMethod(holder.javaClass, "getBinding", accessible = false).invoke(holder) }.getOrNull() ?: holder
                        val mirrorBlurProvider = getOrCacheMirrorBlurProviderField(binding.javaClass)?.let { f ->
                            runCatching { f.get(binding) as? View }.getOrNull()
                        }
                        val topText = runCatching { getOrCacheBindingTopTextField(binding.javaClass)?.get(binding) as? TextView }.getOrNull()
                            ?: runCatching { getOrCacheViewGetTopTextMethod(binding.javaClass)?.invoke(binding) as? TextView }.getOrNull()
                            ?: return@runCatching

                        val sameStyle = parent.policy.controlCenter(true) && parent.sameStyleEnabled
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
                    if (!parent.policy.controlCenter(true)) return@after
                    runCatching {
                        val thisObject = param.thisObject
                        val fromView = getOrCacheAnimatorFromViewField(thisObject.javaClass)?.get(thisObject) ?: return@runCatching
                        val fromText = getOrCacheViewGetTopTextMethod(fromView.javaClass)?.invoke(fromView) as? TextView ?: return@runCatching
                        val getToView = getOrCacheAnimatorGetToViewMethod(thisObject.javaClass) ?: return@runCatching
                        val toView = getToView.invoke(thisObject) ?: return@runCatching
                        val sliderBinding = getOrCacheViewGetSliderBindingMethod(toView.javaClass)?.invoke(toView) ?: return@runCatching
                        val toText = getOrCacheBindingTopTextField(sliderBinding.javaClass)?.get(sliderBinding) as? TextView ?: return@runCatching
                        animatorBounds[thisObject] = AnimatorBounds(
                            fromText.left, fromText.top, fromText.width, fromText.height,
                            toText.left, toText.top, toText.width, toText.height
                        )
                    }.onFailure { t ->
                        Log.e("HyperTweak", "Error in calculateViewValues hook", t)
                    }
                }
            }
        }

        clzBrightnessAnimator?.declaredMethods?.firstOrNull { it.name == "frameCallback" }?.let { method ->
            method.hook {
                after { param ->
                    if (!parent.policy.controlCenter(true)) return@after
                    runCatching {
                        val thisObject = param.thisObject
                        val bounds = animatorBounds[thisObject] ?: return@runCatching
                        val fraction = getOrCacheAnimatorSizeField(thisObject.javaClass)?.get(thisObject) as? Float ?: return@runCatching
                        val left = bounds.fromLeft + (bounds.toLeft - bounds.fromLeft) * fraction
                        val top = bounds.fromTop + (bounds.toTop - bounds.fromTop) * fraction
                        val width = bounds.fromWidth + (bounds.toWidth - bounds.fromWidth) * fraction
                        val height = bounds.fromHeight + (bounds.toHeight - bounds.fromHeight) * fraction

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
        clzBrightnessPanelDelegate?.declaredMethods?.filter { it.name in setOf("prepareShow", "updateIconProgress") }?.forEach { method ->
            method.hook { before { param -> SliderHookHelper.trackExpandedBrightness(param.thisObject) } }
        }
        clzBrightnessPanelDelegate?.declaredMethods?.firstOrNull { it.name == "prepareShow" }?.let { method ->
            method.hook {
                after { param ->
                    if (parent.policy.controlCenter(true)) {
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
                    if (parent.policy.controlCenter(true)) {
                        runCatching {
                            val thisObject = param.thisObject
                            val binding = getOrCacheDelegateBindingField(thisObject.javaClass)?.get(thisObject) ?: return@runCatching
                            val toggleSlider = getOrCacheDelegateToggleSliderField(binding.javaClass)?.get(binding) ?: return@runCatching
                            val topText = getOrCacheDelegateTopTextField(toggleSlider.javaClass)?.get(toggleSlider) as? TextView ?: return@runCatching
                            val slider = getOrCacheDelegateSliderField(toggleSlider.javaClass)?.get(toggleSlider) as? android.widget.SeekBar ?: return@runCatching

                            val level = slider.progress
                            val minLevel = slider.min
                            val maxLevel = slider.max
                            val pct = volumePercent(level, minLevel, maxLevel) ?: return@runCatching
                            setPercentIfChanged(topText, pct)
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
