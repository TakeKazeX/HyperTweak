package com.takekazex.hypertweak.hook.rules

import android.util.Log
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.DexKitManager
import org.luckypray.dexkit.query.enums.StringMatchType

class SliderPercentageHooker(
    private val pluginContext: android.content.Context? = null,
    private val pluginApkPath: String = "",
    private val mainApkPath: String = ""
) : DynamicHooker() {

    fun resolveClass(className: String, initialize: Boolean = false): Class<Any>? {
        val resolvedClass = resolveViaDexKit(className)
        if (resolvedClass != null) {
            @Suppress("UNCHECKED_CAST")
            return resolvedClass as Class<Any>
        }
        return className.toClassOrNull(initialize = initialize)
    }

    private fun resolveViaDexKit(className: String): Class<*>? {
        if (pluginContext == null) return null
        
        return when (className) {
            "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController" -> {
                resolvePluginClass("BrightnessSliderController") { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("BrightnessSliderController", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                }
            }
            "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController" -> {
                resolvePluginClass("VolumeSliderController") { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("VolumeSliderController", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                }
            }
            "miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder" -> {
                resolvePluginClass("ToggleSliderViewHolder") { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("ToggleSliderViewHolder", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                }
            }

            "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelAnimator" -> {
                resolvePluginClass("BrightnessPanelAnimator") { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("BrightnessPanelAnimator", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                }
            }
            "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate" -> {
                resolvePluginClass("BrightnessPanelSliderDelegate") { bridge ->
                    bridge.findClass {
                        searchPackages("miui.systemui.controlcenter")
                        matcher { className("BrightnessPanelSliderDelegate", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                }
            }
            "com.android.systemui.miui.volume.VolumeColumn\$iconColorTransition\$2\$1" -> {
                resolveMainClass("iconColorTransition") { bridge ->
                    bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher { className("VolumeColumn\$iconColorTransition\$2\$1", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                }
            }
            "com.android.systemui.miui.volume.VolumeColumn\$iconBlendColorTransition\$2\$1" -> {
                resolveMainClass("iconBlendColorTransition") { bridge ->
                    bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher { className("VolumeColumn\$iconBlendColorTransition\$2\$1", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                }
            }
            "com.android.systemui.miui.volume.VolumeColumn" -> {
                resolveMainClass("VolumeColumn") { bridge ->
                    bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher { className("VolumeColumn", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                }
            }
            "com.android.systemui.miui.volume.VolumePanelViewController" -> {
                resolveMainClass("VolumePanelViewController") { bridge ->
                    bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher { className("VolumePanelViewController", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                }
            }
            else -> null
        }
    }

    private fun resolvePluginClass(key: String, query: (org.luckypray.dexkit.DexKitBridge) -> String?): Class<*>? {
        if (pluginApkPath.isEmpty()) return null
        val resolved = DexKitManager.resolveClasses(
            cacheDir = pluginContext?.cacheDir,
            apkPath = pluginApkPath,
            classLoader = classLoader,
            queries = mapOf(key to query)
        )
        return resolved[key]
    }

    private fun resolveMainClass(key: String, query: (org.luckypray.dexkit.DexKitBridge) -> String?): Class<*>? {
        if (mainApkPath.isEmpty()) return null
        val resolved = DexKitManager.resolveClasses(
            cacheDir = pluginContext?.cacheDir,
            apkPath = mainApkPath,
            classLoader = classLoader,
            queries = mapOf(key to query)
        )
        return resolved[key]
    }

    override fun onHook() {
        Log.d("HyperTweak", "SliderPercentageHooker dispatcher attaching child hookers")

        // Attach child hookers which perform the actual hooks on the classes
        attach(BrightnessSliderHooker(this))
        attach(VolumeSliderHooker(this))
        attach(CommonSliderHooker(this))

        // Hook TextView.setTextColor to intercept and force color state when unified style is active
        runCatching {
            val mSetTextColorCsl = TextView::class.java.getDeclaredMethod("setTextColor", android.content.res.ColorStateList::class.java)
            mSetTextColorCsl.hook {
                before { param ->
                    val textView = param.thisObject as TextView
                    val sliderType = SliderHookHelper.getTag(textView, "sliderType") as? String
                    if (sliderType != null && Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)) {
                        if (ColorOverrideLock.isSettingColor.get() != true) {
                            val activeColor = SliderHookHelper.getActiveColor(textView.context)
                            param.args[0] = android.content.res.ColorStateList.valueOf(activeColor)
                        }
                    }
                }
            }
        }.onFailure { t ->
            Log.e("HyperTweak", "Failed to hook TextView.setTextColor(ColorStateList)", t)
        }

        runCatching {
            val mSetTextColorInt = TextView::class.java.getDeclaredMethod("setTextColor", Int::class.javaPrimitiveType)
            mSetTextColorInt.hook {
                before { param ->
                    val textView = param.thisObject as TextView
                    val sliderType = SliderHookHelper.getTag(textView, "sliderType") as? String
                    if (sliderType != null && Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)) {
                        if (ColorOverrideLock.isSettingColor.get() != true) {
                            val activeColor = SliderHookHelper.getActiveColor(textView.context)
                            param.args[0] = activeColor
                        }
                    }
                }
            }
        }.onFailure { t ->
            Log.e("HyperTweak", "Failed to hook TextView.setTextColor(int)", t)
        }
    }
}
