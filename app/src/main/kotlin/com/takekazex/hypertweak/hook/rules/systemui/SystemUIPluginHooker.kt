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
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver

object SystemUIPluginHooker : StaticHooker() {
    private data class PluginHookSession(
        val state: HotReloadPluginState,
        val hooker: SliderPercentageHooker
    )

    private val activeSessions = ConcurrentHashMap<Any, PluginHookSession>()

    override fun onPrepareHotReload() {
        activeSessions.clear()
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
        CompatibleMethodResolver.find(clzPluginInstance, "loadPlugin", parameterTypes = emptyList())?.let { method ->
            method.hook {
                after { param ->
                    HookFailurePolicy.open("SystemUIPlugin", "loadPlugin", Unit) {
                        val pluginInstance = param.thisObject
                        val componentName = pluginInstance.javaClass.getDeclaredField("mComponentName")
                            .apply { isAccessible = true }.get(pluginInstance) as? ComponentName

                        if (componentName != null && isControlCenterPlugin(componentName)) {
                            attachPluginHooker(pluginInstance, componentName)
                        }
                    }
                }
            }
        } ?: run {
            DebugLog.hookSkipped("SystemUIPlugin", "PluginInstance#loadPlugin", "method not found")
            Log.e("HyperTweak", "SystemUIPluginHooker: loadPlugin method not found")
        }

        // Hook unloadPlugin() to release hooks and prevent leaks when plugin is unloaded
        CompatibleMethodResolver.find(clzPluginInstance, "unloadPlugin", parameterTypes = emptyList())?.let { method ->
            method.hook {
                before { param ->
                    HookFailurePolicy.open("SystemUIPlugin", "unloadPlugin", Unit) {
                        val pluginInstance = param.thisObject
                        val session = activeSessions.remove(pluginInstance)
                        if (session != null) {
                            runCatching { session.hooker.prepareForHotReload() }
                            detach(session.hooker)
                        }
                    }
                }
            }
        } ?: run {
            DebugLog.hookSkipped("SystemUIPlugin", "PluginInstance#unloadPlugin", "method not found")
            Log.e("HyperTweak", "SystemUIPluginHooker: unloadPlugin method not found")
        }
    }

    fun snapshotHotReloadPlugins(): List<HotReloadPluginState> {
        return activeSessions.values.map { it.state }
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
        val plugin = sequenceOf("mPlugin", "mLoadedPlugin").mapNotNull { fieldName ->
            runCatching { pluginInstance.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(pluginInstance) }.getOrNull()
        }.firstOrNull()
        val classLoader = plugin?.javaClass?.classLoader
            ?: runCatching { pluginInstance.javaClass.getDeclaredField("mClassLoader").apply { isAccessible = true }.get(pluginInstance) as? ClassLoader }.getOrNull()
        if (classLoader == null) {
            DebugLog.hookFailed("SystemUIPlugin", "PluginInstance#loadPlugin classLoader", null)
            Log.e("HyperTweak", "SystemUIPluginHooker: failed to extract loaded plugin ClassLoader")
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
        if (activeSessions.containsKey(state.pluginInstance)) return
        val hooker = if (state.appContext != null && state.pluginApkPath.isNotEmpty()) {
            SliderPercentageHooker(state.appContext, state.pluginApkPath)
        } else {
            Log.w("HyperTweak", "SystemUIPluginHooker: Missing context or APK paths, instantiating with default fallback")
            SliderPercentageHooker()
        }

        activeSessions[state.pluginInstance] = PluginHookSession(state, hooker)
        attach(hooker, state.classLoader)
        DebugLog.d("SystemUIPlugin", "attached plugin hook ${state.componentPackage}/${state.componentClass}")
    }

    private fun isControlCenterPlugin(componentName: ComponentName): Boolean {
        return componentName.packageName == "miui.systemui.plugin" ||
            componentName.className == "miui.systemui.controlcenter.MiuiControlCenter"
    }
}
