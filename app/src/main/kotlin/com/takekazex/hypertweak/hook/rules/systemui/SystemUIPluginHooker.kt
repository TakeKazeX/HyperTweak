package com.takekazex.hypertweak.hook.rules.systemui

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.HotReloadPluginState
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.slider.SliderPercentageHooker
import com.takekazex.hypertweak.hook.rules.slider.ControlCenterCornerHooker
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.ConcurrentHashMap
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver

object SystemUIPluginHooker : StaticHooker() {
    private data class PluginHookSession(
        val state: HotReloadPluginState,
        val sliderPercentHooker: SliderPercentageHooker,
        val cornerHooker: ControlCenterCornerHooker,
        val cardsEditHooker: ControlCenterCardsEditHooker?
    )

    private val activeSessions = ConcurrentHashMap<Any, PluginHookSession>()

    override fun onPrepareHotReload() {
        activeSessions.clear()
    }

    override fun onHook() {
        // The control-center editor cards feature (编辑与排序 visibility + drag reorder) is
        // gated on its master switch; the hooker attaches only when the switch is already on at
        // plugin load, so enabling it the first time needs a SystemUI restart. Every callback
        // inside the hooker re-reads the switch live, so turning it off applies without one.

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
                        // OS4 renamed the PluginInstance fields from m-prefixed private to public
                        // short names; accept both so one hooker covers both plugin generations.
                        val componentName = readPluginField(pluginInstance, "mComponentName", "componentName") as? ComponentName

                        Log.i("HyperTweak", "SystemUIPluginHooker: loadPlugin component=$componentName")
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
                            runCatching { session.sliderPercentHooker.prepareForHotReload() }
                            runCatching { session.cornerHooker.prepareForHotReload() }
                            session.cardsEditHooker?.let { cardsEdit ->
                                runCatching { cardsEdit.prepareForHotReload() }
                                detach(cardsEdit)
                            }
                            detach(session.sliderPercentHooker)
                            detach(session.cornerHooker)
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
        // OS3 exposes mPluginFactory/mPlugin/mAppContext on PluginInstance; OS4 replaces them
        // with public componentName/pluginFactory/pluginData{plugin, context} fields.
        val pluginData = readPluginField(pluginInstance, "pluginData")
        val plugin = readPluginField(pluginInstance, "mPlugin", "mLoadedPlugin")
            ?: pluginData?.let { readPluginField(it, "plugin") }
        val appContext = readPluginField(pluginInstance, "mAppContext") as? Context
            ?: pluginData?.let { readPluginField(it, "context") as? Context }
        // The loaded plugin object can be a host-side proxy on OS4. Prefer the plugin context's
        // PathClassLoader, which is the loader that actually owns MainPanelAdapter and its nested
        // ItemTouchHelper callback classes.
        val classLoader = (appContext as? ContextWrapper)?.classLoader
            ?: plugin?.javaClass?.classLoader
            ?: readPluginField(pluginInstance, "mClassLoader") as? ClassLoader
        Log.i(
            "HyperTweak",
            "SystemUIPluginHooker: loaded component=$componentName " +
                "plugin=${plugin?.javaClass?.name} loader=${classLoader?.javaClass?.name}"
        )
        if (classLoader == null) {
            DebugLog.hookFailed("SystemUIPlugin", "PluginInstance#loadPlugin classLoader", null)
            Log.e("HyperTweak", "SystemUIPluginHooker: failed to extract loaded plugin ClassLoader")
            return
        }
        runCatching {
            classLoader.loadClass("miui.systemui.controlcenter.panel.main.recyclerview.MainPanelAdapter")
            Log.i("HyperTweak", "SystemUIPluginHooker: plugin MainPanelAdapter resolved")
        }.onFailure { t ->
            Log.w("HyperTweak", "SystemUIPluginHooker: plugin MainPanelAdapter unresolved", t)
        }

        val pluginFactory = readPluginField(pluginInstance, "mPluginFactory", "pluginFactory")
        val mAppInfo = pluginFactory?.let { readPluginField(it, "mAppInfo", "pluginAppInfo") as? ApplicationInfo }
        attachPluginHooker(
            HotReloadPluginState(
                pluginInstance = pluginInstance,
                componentPackage = componentName.packageName,
                componentClass = componentName.className,
                classLoader = classLoader,
                appContext = appContext,
                pluginApkPath = mAppInfo?.sourceDir ?: ""
            )
        )
    }

    /** Reads the first non-null field value among the candidate names, tolerating renamed fields across plugin generations. */
    private fun readPluginField(obj: Any, vararg names: String): Any? {
        for (name in names) {
            val value = runCatching {
                obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    private fun attachPluginHooker(state: HotReloadPluginState) {
        if (activeSessions.containsKey(state.pluginInstance)) return
        val hooker = if (state.appContext != null && state.pluginApkPath.isNotEmpty()) {
            SliderPercentageHooker(state.appContext, state.pluginApkPath)
        } else {
            Log.w("HyperTweak", "SystemUIPluginHooker: Missing context or APK paths, instantiating with default fallback")
            SliderPercentageHooker()
        }
        val cornerHooker = if (state.appContext != null && state.pluginApkPath.isNotEmpty()) {
            ControlCenterCornerHooker(state.appContext, state.pluginApkPath)
        } else {
            ControlCenterCornerHooker()
        }
        val cardsEditHooker = if (Preferences.getBoolean(Preferences.KEY_CC_EDIT_ENABLED, false)) {
            ControlCenterCardsEditHooker()
        } else {
            null
        }

        activeSessions[state.pluginInstance] = PluginHookSession(state, hooker, cornerHooker, cardsEditHooker)
        // Each attach is its own failure boundary: a throw from one hooker's onHook (e.g. a
        // DexKit scan hiccup) must not skip the other — that was silently leaving the corner
        // hooker uninstalled while the session entry suppressed later retries.
        runCatching { attach(hooker, state.classLoader) }
            .onFailure { t ->
                DebugLog.e("SystemUIPlugin", "failed to attach slider-percent plugin hook", t)
                Log.e("HyperTweak", "SystemUIPluginHooker: slider-percent attach failed", t)
            }
        runCatching { attach(cornerHooker, state.classLoader) }
            .onFailure { t ->
                DebugLog.e("SystemUIPlugin", "failed to attach corner plugin hook", t)
                Log.e("HyperTweak", "SystemUIPluginHooker: corner attach failed", t)
            }
        if (cardsEditHooker != null) {
            runCatching { attach(cardsEditHooker, state.classLoader) }
                .onFailure { t ->
                    DebugLog.e("SystemUIPlugin", "failed to attach cards-edit plugin hook", t)
                    Log.e("HyperTweak", "SystemUIPluginHooker: cards-edit attach failed", t)
                }
        }
        DebugLog.d("SystemUIPlugin", "attached plugin hook ${state.componentPackage}/${state.componentClass}")
    }

    private fun isControlCenterPlugin(componentName: ComponentName): Boolean {
        return componentName.packageName == "miui.systemui.plugin" ||
            componentName.className == "miui.systemui.controlcenter.MiuiControlCenter"
    }
}
