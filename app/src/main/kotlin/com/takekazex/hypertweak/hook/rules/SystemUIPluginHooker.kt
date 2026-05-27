package com.takekazex.hypertweak.hook.rules

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.takekazex.hypertweak.hook.base.StaticHooker
import java.util.concurrent.ConcurrentHashMap

object SystemUIPluginHooker : StaticHooker() {
    private val activePluginHookers = ConcurrentHashMap<Any, SliderPercentageHooker>()

    override fun onHook() {
        Log.d("HyperTweak", "SystemUIPluginHooker: attaching hooks")

        val clzPluginInstance = "com.android.systemui.shared.plugins.PluginInstance".toClassOrNull()
        if (clzPluginInstance == null) {
            Log.e("HyperTweak", "SystemUIPluginHooker: com.android.systemui.shared.plugins.PluginInstance class not found")
            return
        }

        // Hook loadPlugin() to capture the plugin ClassLoader after loading
        clzPluginInstance.declaredMethods.firstOrNull { it.name == "loadPlugin" }?.let { method ->
            Log.d("HyperTweak", "SystemUIPluginHooker: Hooking loadPlugin")
            method.hook {
                after { param ->
                    runCatching {
                        val pluginInstance = param.thisObject
                        val componentName = pluginInstance.javaClass.getDeclaredField("mComponentName")
                            .apply { isAccessible = true }.get(pluginInstance) as? ComponentName

                        Log.d("HyperTweak", "SystemUIPluginHooker: loadPlugin called for component: $componentName")

                        if (componentName != null && componentName.className == "miui.systemui.controlcenter.MiuiControlCenter") {
                            Log.d("HyperTweak", "SystemUIPluginHooker: Control Center plugin loaded: $componentName")
                            
                            val mPluginFactory = pluginInstance.javaClass.getDeclaredField("mPluginFactory")
                                .apply { isAccessible = true }.get(pluginInstance)
                            val mClassLoaderFactory = mPluginFactory.javaClass.getDeclaredField("mClassLoaderFactory")
                                .apply { isAccessible = true }.get(mPluginFactory)
                            
                            val classLoader = (mClassLoaderFactory as? java.util.function.Supplier<*>)?.get() as? ClassLoader

                            if (classLoader != null) {
                                Log.d("HyperTweak", "SystemUIPluginHooker: Extracted ClassLoader: $classLoader")
                                
                                runCatching {
                                    val clz = classLoader.loadClass("miui.systemui.util.MiuiColorBlendToken")
                                    Log.d("HyperTweak", "MiuiColorBlendToken methods in plugin:")
                                    clz.declaredMethods.forEach { method ->
                                        Log.d("HyperTweak", "  Method: ${method.name} with params: ${method.parameterTypes.map { it.name }}")
                                    }
                                }.onFailure { t ->
                                    Log.e("HyperTweak", "Failed to dump MiuiColorBlendToken methods in plugin", t)
                                }

                                runCatching {
                                    val clz = classLoader.loadClass("miui.systemui.util.MiBlurCompat")
                                    Log.d("HyperTweak", "MiBlurCompat methods in plugin:")
                                    clz.declaredMethods.forEach { method ->
                                        Log.d("HyperTweak", "  Method: ${method.name} with params: ${method.parameterTypes.map { it.name }}")
                                    }
                                }.onFailure { t ->
                                    Log.e("HyperTweak", "Failed to dump MiBlurCompat methods in plugin", t)
                                }

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
                                    val mainApkPath = this@SystemUIPluginHooker.hookParam.appInfo?.sourceDir ?: ""
                                    
                                    val hooker = if (mAppContext != null && pluginApkPath.isNotEmpty() && mainApkPath.isNotEmpty()) {
                                        Log.d("HyperTweak", "SystemUIPluginHooker: Instantiating SliderPercentageHooker with DexKit support")
                                        SliderPercentageHooker(mAppContext, pluginApkPath, mainApkPath)
                                    } else {
                                        Log.w("HyperTweak", "SystemUIPluginHooker: Missing context or APK paths, instantiating with default fallback")
                                        SliderPercentageHooker()
                                    }
                                    
                                    activePluginHookers[pluginInstance] = hooker
                                    attach(hooker, classLoader)
                                    Log.d("HyperTweak", "SystemUIPluginHooker: Attached SliderPercentageHooker to plugin ClassLoader")
                                }
                            } else {
                                Log.e("HyperTweak", "SystemUIPluginHooker: Failed to extract ClassLoader from mClassLoaderFactory")
                            }
                        }
                    }.onFailure { t ->
                        Log.e("HyperTweak", "SystemUIPluginHooker: Error in loadPlugin hook", t)
                    }
                }
            }
        } ?: Log.e("HyperTweak", "SystemUIPluginHooker: loadPlugin method not found")

        // Hook unloadPlugin() to release hooks and prevent leaks when plugin is unloaded
        clzPluginInstance.declaredMethods.firstOrNull { it.name == "unloadPlugin" }?.let { method ->
            Log.d("HyperTweak", "SystemUIPluginHooker: Hooking unloadPlugin")
            method.hook {
                before { param ->
                    runCatching {
                        val pluginInstance = param.thisObject
                        val hooker = activePluginHookers.remove(pluginInstance)
                        if (hooker != null) {
                            Log.d("HyperTweak", "SystemUIPluginHooker: Control Center plugin unloaded, detaching and disabling hooker")
                            detach(hooker)
                        }
                    }.onFailure { t ->
                        Log.e("HyperTweak", "SystemUIPluginHooker: Error in unloadPlugin hook", t)
                    }
                }
            }
        } ?: Log.e("HyperTweak", "SystemUIPluginHooker: unloadPlugin method not found")
    }
}
