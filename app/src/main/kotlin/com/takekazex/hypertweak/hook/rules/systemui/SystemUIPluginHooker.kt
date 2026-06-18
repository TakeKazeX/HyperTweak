package com.takekazex.hypertweak.hook.rules.systemui

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.slider.SliderPercentageHooker
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.ConcurrentHashMap

object SystemUIPluginHooker : StaticHooker() {
    private val activePluginHookers = ConcurrentHashMap<Any, SliderPercentageHooker>()

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) {
            DebugLog.hookSkipped("SystemUIPlugin", "control center plugin hooks", "disabled")
            return
        }

        val clzPluginInstance = "com.android.systemui.shared.plugins.PluginInstance".toClassOrNull()
        if (clzPluginInstance == null) {
            DebugLog.hookSkipped("SystemUIPlugin", "PluginInstance", "class not found")
            Log.e("HyperTweak", "SystemUIPluginHooker: com.android.systemui.shared.plugins.PluginInstance class not found")
            return
        }

        // Hook loadPlugin() to capture the plugin ClassLoader after loading
        clzPluginInstance.declaredMethods.firstOrNull { it.name == "loadPlugin" }?.let { method ->
            method.hook {
                after { param ->
                    runCatching {
                        val pluginInstance = param.thisObject
                        val componentName = pluginInstance.javaClass.getDeclaredField("mComponentName")
                            .apply { isAccessible = true }.get(pluginInstance) as? ComponentName

                        if (componentName != null && (componentName.packageName == "miui.systemui.plugin" || componentName.className == "miui.systemui.controlcenter.MiuiControlCenter")) {
                            
                            val mPluginFactory = pluginInstance.javaClass.getDeclaredField("mPluginFactory")
                                .apply { isAccessible = true }.get(pluginInstance)
                            val mClassLoaderFactory = mPluginFactory.javaClass.getDeclaredField("mClassLoaderFactory")
                                .apply { isAccessible = true }.get(mPluginFactory)
                            
                            val classLoader = (mClassLoaderFactory as? java.util.function.Supplier<*>)?.get() as? ClassLoader

                            if (classLoader != null) {

                                if (!activePluginHookers.containsKey(pluginInstance)) {
                                    val mAppContext = runCatching {
                                        pluginInstance.javaClass.getDeclaredField("mAppContext")
                                            .apply { isAccessible = true }.get(pluginInstance) as? Context
                                    }.getOrNull()
                                    val mPluginFactory2 = runCatching {
                                        pluginInstance.javaClass.getDeclaredField("mPluginFactory")
                                            .apply { isAccessible = true }.get(pluginInstance)
                                    }.getOrNull()
                                    val mAppInfo = runCatching {
                                        mPluginFactory2?.javaClass?.getDeclaredField("mAppInfo")
                                            ?.apply { isAccessible = true }?.get(mPluginFactory2) as? ApplicationInfo
                                    }.getOrNull()
                                    
                                    val pluginApkPath = mAppInfo?.sourceDir ?: ""

                                    val hooker = if (mAppContext != null && pluginApkPath.isNotEmpty()) {
                                        SliderPercentageHooker(mAppContext, pluginApkPath)
                                    } else {
                                        Log.w("HyperTweak", "SystemUIPluginHooker: Missing context or APK paths, instantiating with default fallback")
                                        SliderPercentageHooker()
                                    }
                                    
                                    activePluginHookers[pluginInstance] = hooker
                                    attach(hooker, classLoader)
                                }
                            } else {
                                DebugLog.hookFailed("SystemUIPlugin", "PluginInstance#loadPlugin classLoader", null)
                                Log.e("HyperTweak", "SystemUIPluginHooker: Failed to extract ClassLoader from mClassLoaderFactory")
                            }
                        }
                    }.onFailure { t ->
                        Log.e("HyperTweak", "SystemUIPluginHooker: Error in loadPlugin hook", t)
                    }
                }
            }
        } ?: run {
            DebugLog.hookSkipped("SystemUIPlugin", "PluginInstance#loadPlugin", "method not found")
            Log.e("HyperTweak", "SystemUIPluginHooker: loadPlugin method not found")
        }

        // Hook unloadPlugin() to release hooks and prevent leaks when plugin is unloaded
        clzPluginInstance.declaredMethods.firstOrNull { it.name == "unloadPlugin" }?.let { method ->
            method.hook {
                before { param ->
                    runCatching {
                        val pluginInstance = param.thisObject
                        val hooker = activePluginHookers.remove(pluginInstance)
                        if (hooker != null) {
                            detach(hooker)
                        }
                    }.onFailure { t ->
                        Log.e("HyperTweak", "SystemUIPluginHooker: Error in unloadPlugin hook", t)
                    }
                }
            }
        } ?: run {
            DebugLog.hookSkipped("SystemUIPlugin", "PluginInstance#unloadPlugin", "method not found")
            Log.e("HyperTweak", "SystemUIPluginHooker: unloadPlugin method not found")
        }
    }
}
