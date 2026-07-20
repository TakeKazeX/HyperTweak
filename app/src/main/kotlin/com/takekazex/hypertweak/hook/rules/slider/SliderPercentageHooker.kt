package com.takekazex.hypertweak.hook.rules.slider

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadMode
import org.luckypray.dexkit.query.enums.StringMatchType

class SliderPercentageHooker(
    private val pluginContext: android.content.Context? = null,
    private val pluginApkPath: String = ""
) : DynamicHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    @Volatile
    var showPercentageEnabled: Boolean = false
        private set

    @Volatile
    var sameStyleEnabled: Boolean = false
        private set

    private val resolvedPluginClasses by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (pluginContext == null || pluginApkPath.isEmpty()) emptyMap() else DexKitManager.resolveClasses(
            cacheDir = pluginContext.cacheDir,
            apkPath = pluginApkPath,
            classLoader = classLoader,
            queries = pluginClassQueries()
        )
    }

    fun resolveClass(className: String, initialize: Boolean = false): Class<Any>? {
        className.toClassOrNull(initialize = initialize)?.let { return it }

        val resolvedClass = resolveViaDexKit(className)
        if (resolvedClass != null) {
            @Suppress("UNCHECKED_CAST")
            return resolvedClass as Class<Any>
        }
        return null
    }

    private fun resolveViaDexKit(className: String): Class<*>? {
        if (pluginContext == null) return null
        
        val key = pluginClassKey(className) ?: return null
        return resolvedPluginClasses[key]
    }

    private fun pluginClassQueries() = mapOf<String, (org.luckypray.dexkit.DexKitBridge) -> String?>(
            "BrightnessSliderController" to { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("BrightnessSliderController", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "VolumeSliderController" to { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("VolumeSliderController", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "ToggleSliderViewHolder" to { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("ToggleSliderViewHolder", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "BrightnessPanelAnimator" to { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("BrightnessPanelAnimator", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "BrightnessPanelSliderDelegate" to { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("BrightnessPanelSliderDelegate", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "iconColorTransition" to { bridge ->
                    bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher { className("VolumeColumn\$iconColorTransition\$2\$1", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "iconBlendColorTransition" to { bridge ->
                    bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher { className("VolumeColumn\$iconBlendColorTransition\$2\$1", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "VolumeColumn" to { bridge ->
                    bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher { className("VolumeColumn", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
            "VolumePanelViewController" to { bridge ->
                    bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher { className("VolumePanelViewController", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                }
        )

    private fun pluginClassKey(className: String): String? = when (className) {
        "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController" -> "BrightnessSliderController"
        "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController" -> "VolumeSliderController"
        "miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder" -> "ToggleSliderViewHolder"
        "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelAnimator" -> "BrightnessPanelAnimator"
        "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate" -> "BrightnessPanelSliderDelegate"
        "com.android.systemui.miui.volume.VolumeColumn\$iconColorTransition\$2\$1" -> "iconColorTransition"
        "com.android.systemui.miui.volume.VolumeColumn\$iconBlendColorTransition\$2\$1" -> "iconBlendColorTransition"
        "com.android.systemui.miui.volume.VolumeColumn" -> "VolumeColumn"
        "com.android.systemui.miui.volume.VolumePanelViewController" -> "VolumePanelViewController"
        else -> null
    }

    override fun onPrepareHotReload() {
        SliderHookHelper.clearHotReloadCaches()
    }

    override fun onHook() {
        showPercentageEnabled = Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)
        sameStyleEnabled = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
        SliderHookHelper.sameStyleEnabled = sameStyleEnabled
        if (!showPercentageEnabled) return

        // Attach child hookers which perform the actual hooks on the classes
        attach(BrightnessSliderHooker(this))
        attach(VolumeSliderHooker(this))
        attach(CommonSliderHooker(this))

    }
}
