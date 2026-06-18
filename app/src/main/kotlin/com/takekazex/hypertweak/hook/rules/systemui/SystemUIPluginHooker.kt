package com.takekazex.hypertweak.hook.rules.systemui

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.HotReloadPluginState
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.slider.SliderPercentageHooker
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.ConcurrentHashMap

object SystemUIPluginHooker : StaticHooker() {
    private val activePluginHookers = ConcurrentHashMap<Any, SliderPercentageHooker>()
    private val activePluginStates = ConcurrentHashMap<Any, HotReloadPluginState>()

    override fun onPrepareHotReload() {
        activePluginStates.clear()
        activePluginHookers.clear()
    }

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

                        if (componentName != null && isControlCenterPlugin(componentName)) {
                            attachPluginHooker(pluginInstance, componentName)
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
                        activePluginStates.remove(pluginInstance)
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

    fun snapshotHotReloadPlugins(): List<HotReloadPluginState> {
        return activePluginStates.values.toList()
    }

    fun restoreHotReloadPlugins(states: List<HotReloadPluginState>) {
        states.forEach { state ->
            runCatching {
                attachPluginHooker(state)
            }.onFailure { t ->
                DebugLog.e("SystemUIPlugin", "failed to restore plugin hook ${state.componentPackage}/${state.componentClass}", t)
            }
        }
    }

    private fun attachPluginHooker(pluginInstance: Any, componentName: ComponentName) {
        val mPluginFactory = pluginInstance.javaClass.getDeclaredField("mPluginFactory")
            .apply { isAccessible = true }.get(pluginInstance)
        val mClassLoaderFactory = mPluginFactory.javaClass.getDeclaredField("mClassLoaderFactory")
            .apply { isAccessible = true }.get(mPluginFactory)
        val classLoader = (mClassLoaderFactory as? java.util.function.Supplier<*>)?.get() as? ClassLoader
        if (classLoader == null) {
            DebugLog.hookFailed("SystemUIPlugin", "PluginInstance#loadPlugin classLoader", null)
            Log.e("HyperTweak", "SystemUIPluginHooker: Failed to extract ClassLoader from mClassLoaderFactory")
            return
        }

        val mAppContext = runCatching {
            pluginInstance.javaClass.getDeclaredField("mAppContext")
                .apply { isAccessible = true }.get(pluginInstance) as? Context
        }.getOrNull()
        val mAppInfo = runCatching {
            mPluginFactory.javaClass.getDeclaredField("mAppInfo")
                .apply { isAccessible = true }.get(mPluginFactory) as? ApplicationInfo
        }.getOrNull()
        attachPluginHooker(
            HotReloadPluginState(
                pluginInstance = pluginInstance,
                componentPackage = componentName.packageName,
                componentClass = componentName.className,
                classLoader = classLoader,
                appContext = mAppContext,
                pluginApkPath = mAppInfo?.sourceDir ?: ""
            )
        )
    }

    private fun attachPluginHooker(state: HotReloadPluginState) {
        if (activePluginHookers.containsKey(state.pluginInstance)) return
        val hooker = if (state.appContext != null && state.pluginApkPath.isNotEmpty()) {
            SliderPercentageHooker(state.appContext, state.pluginApkPath)
        } else {
            Log.w("HyperTweak", "SystemUIPluginHooker: Missing context or APK paths, instantiating with default fallback")
            SliderPercentageHooker()
        }

        activePluginStates[state.pluginInstance] = state
        activePluginHookers[state.pluginInstance] = hooker
        attach(hooker, state.classLoader)
        DebugLog.d("SystemUIPlugin", "attached plugin hook ${state.componentPackage}/${state.componentClass}")
    }

    private fun isControlCenterPlugin(componentName: ComponentName): Boolean {
        return componentName.packageName == "miui.systemui.plugin" ||
            componentName.className == "miui.systemui.controlcenter.MiuiControlCenter"
    }
}
