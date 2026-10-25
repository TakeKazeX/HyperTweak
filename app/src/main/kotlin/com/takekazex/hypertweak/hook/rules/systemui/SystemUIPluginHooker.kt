package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.hook.rules.systemui.island.ThreeIslandHooker
import com.takekazex.hypertweak.hook.rules.systemui.island.IslandTimeFontHooker
import com.takekazex.hypertweak.hook.rules.systemui.island.LiveUpdateIconHooker
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.HotReloadPluginState
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.aod.AodStatusIconHooker
import com.takekazex.hypertweak.hook.rules.slider.SliderPercentageHooker
import com.takekazex.hypertweak.hook.rules.slider.ControlCenterCornerHooker
import com.takekazex.hypertweak.hook.rules.systemui.ControlCenterCardResizeHooker
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.ConcurrentHashMap
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.HookSessionRegistry
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver

object SystemUIPluginHooker : StaticHooker() {
    private data class PluginHookSession(
        val sliderPercentHooker: SliderPercentageHooker,
        val cornerHooker: ControlCenterCornerHooker,
        val volumeKeyHooker: ControlCenterVolumeKeyHooker,
        val tileMessageHooker: TileStateMessageHooker,
        val superIslandHooker: ControlCenterSuperIslandHooker,
        val threeIslandHooker: ThreeIslandHooker,
        val liveUpdateIconHooker: LiveUpdateIconHooker,
        val timeFontHooker: IslandTimeFontHooker,
        var cardsEditHooker: ControlCenterCardsEditHooker? = null,
        var cardResizeHooker: ControlCenterCardResizeHooker? = null,
        var mediaDeviceIconHooker: MediaPlayerDeviceIconHooker? = null,
        val mediaSuperIslandWhitelistHooker: MediaSuperIslandWhitelistHooker,
        /** The plugin's own loader; optional sub-hookers attach with the same one. */
        val classLoader: ClassLoader
    ) {
        fun attachCardsEdit(hooker: ControlCenterCardsEditHooker?) { cardsEditHooker = hooker }
        fun attachCardResize(hooker: ControlCenterCardResizeHooker?) { cardResizeHooker = hooker }
        fun attachMediaDeviceIcon(hooker: MediaPlayerDeviceIconHooker?) { mediaDeviceIconHooker = hooker }
    }

    private data class AodPluginSession(
        val state: HotReloadPluginState,
        val hooker: AodStatusIconHooker
    )

    private val activeSessions = HookSessionRegistry<HotReloadPluginState, PluginHookSession>()
    private val activeAodSessions = ConcurrentHashMap<Any, AodPluginSession>()

    /** Settings change policies, not host contracts. Keep hooks and their live owners in place. */
    fun reloadIslandSettings(): Boolean {
        activeSessions.sessions().forEach { session ->
            session.threeIslandHooker.refreshSettings()
            session.superIslandHooker.refreshSettings()
            session.liveUpdateIconHooker.refreshSettings()
            session.timeFontHooker.restoreIslandOwners(session.threeIslandHooker.snapshotOwners())
        }
        // A plugin arriving later attaches directly against the current committed snapshot.
        return true
    }

    /**
     * Applies an accepted AOD snapshot to every living AOD plugin session. Sessions keep their
     * parent-owned hooks, so this never recreates a hooker or rescans the plugin APK.
     */
    fun reloadAodSettings(): Boolean {
        activeAodSessions.values.forEach { session ->
            runCatching { session.hooker.refreshSettings() }
                .onFailure { DebugLog.w("SystemUIPlugin", "AOD plugin refresh failed", it) }
        }
        return true
    }

    /**
     * Applies accepted control-center plugin settings (slider percentage, corner radius, editor,
     * resize). A session's sub-hookers are attached for the session lifetime, so this only
     * refreshes their policies; nothing is recreated and the parent keeps handle ownership.
     * A sub-hooker that was chosen at session creation is attached here on the first enable.
     */
    fun reloadControlCenterSettings(): Boolean {
        activeSessions.sessions().forEach { session ->
            runCatching {
                session.sliderPercentHooker.refreshSettings()
                session.cornerHooker.refreshSettings()
                session.tileMessageHooker.refreshSettings()
            }.onFailure { DebugLog.w("SystemUIPlugin", "control-center settings refresh failed", it) }
            // Optional sub-hookers are attached on the first enable, then refresh in place. The
            // attach happens before the refresh so a freshly attached hooker also sees the
            // committed value for the surfaces that are already on screen.
            runCatching { ensureOptionalHookers(session) }
                .onFailure { DebugLog.w("SystemUIPlugin", "optional plugin hooks attach failed", it) }
            listOf(session.cardsEditHooker, session.cardResizeHooker, session.mediaDeviceIconHooker)
                .filterNotNull()
                .forEach { hooker ->
                    runCatching {
                        when (hooker) {
                            is ControlCenterCardsEditHooker -> hooker.refreshSettings()
                            is ControlCenterCardResizeHooker -> hooker.refreshSettings()
                            is MediaPlayerDeviceIconHooker -> hooker.refreshSettings()
                        }
                    }.onFailure { DebugLog.w("SystemUIPlugin", "optional plugin hook refresh failed", it) }
                }
        }
        return true
    }

    /** Attaches an optional sub-hooker the first time its switch is on; the switch alone skips it. */
    private fun ensureOptionalHookers(session: PluginHookSession) {
        val edit = Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED, false)
        val resize = Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_RESIZE_ENABLED, false)
        val deviceIcon = Preferences.getBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, false)
        val loader = session.classLoader
        if (edit && session.cardsEditHooker == null) {
            val hooker = ControlCenterCardsEditHooker()
            runCatching { attach(hooker, loader) }.onSuccess { session.attachCardsEdit(hooker) }
                .onFailure { DebugLog.e("SystemUIPlugin", "failed to attach cards-edit plugin hook", it) }
        }
        if (resize && session.cardResizeHooker == null) {
            val hooker = ControlCenterCardResizeHooker()
            runCatching { attach(hooker, loader) }.onSuccess { session.attachCardResize(hooker) }
                .onFailure { DebugLog.e("SystemUIPlugin", "failed to attach card-resize plugin hook", it) }
        }
        if (deviceIcon && session.mediaDeviceIconHooker == null) {
            val hooker = MediaPlayerDeviceIconHooker()
            runCatching { attach(hooker, loader) }.onSuccess { session.attachMediaDeviceIcon(hooker) }
                .onFailure { DebugLog.e("SystemUIPlugin", "failed to attach media-device-icon plugin hook", it) }
        }
    }

    override fun saveHotReloadState(): Any = activeSessions.bindings().map { (owner, session) ->
        listOf(owner, session.threeIslandHooker.snapshotOwners(), session.superIslandHooker.snapshotControllers(),
            session.liveUpdateIconHooker.snapshotHolders(), session.timeFontHooker.snapshotHolders(),
            session.tileMessageHooker.snapshotSlots())
    }

    override fun restoreHotReloadState(state: Any?) {
        (state as? List<*>)?.forEach { item ->
            val entry = item as? List<*> ?: return@forEach
            val session = activeSessions[entry.firstOrNull()] ?: return@forEach
            session.threeIslandHooker.restoreOwners((entry.getOrNull(1) as? List<*>)?.filterNotNull().orEmpty())
            session.superIslandHooker.restoreControllers((entry.getOrNull(2) as? List<*>)?.filterNotNull().orEmpty())
            session.liveUpdateIconHooker.restoreHolders((entry.getOrNull(3) as? List<*>)?.filterNotNull().orEmpty())
            session.timeFontHooker.restoreHolders((entry.getOrNull(4) as? List<*>)?.filterNotNull().orEmpty())
            session.tileMessageHooker.restoreSlots(entry.getOrNull(5) as? List<*> ?: emptyList<Any>())
            session.timeFontHooker.restoreIslandOwners((entry.getOrNull(1) as? List<*>)?.filterNotNull().orEmpty())
        }
    }

    override fun onPrepareHotReload() {
        activeSessions.clear()
        activeAodSessions.clear()
    }

    override fun onHook() {
        // The control-center editor cards feature (编辑与排序 visibility + drag reorder) is
        // gated on its master switch; the hooker attaches only when the switch is already on at
        // plugin load, so enabling it the first time needs a SystemUI restart. Every callback
        // inside the hooker re-reads the switch live, so turning it off applies without one.

        val clzPluginInstance = "com.android.systemui.shared.plugins.PluginInstance".toClassOrNull()
        if (clzPluginInstance == null) {
            DebugLog.hookSkipped("SystemUIPlugin", "PluginInstance", "class not found")
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

                        DebugLog.d("SystemUIPlugin", "loadPlugin component=$componentName")
                        if (componentName != null &&
                            (isControlCenterPlugin(componentName) || isAodPlugin(componentName))) {
                            attachPluginHooker(pluginInstance, componentName)
                        }
                    }
                }
            }
        } ?: run {
            DebugLog.hookSkipped("SystemUIPlugin", "PluginInstance#loadPlugin", "method not found")
        }

        // Hook unloadPlugin() to release hooks and prevent leaks when plugin is unloaded
        CompatibleMethodResolver.find(clzPluginInstance, "unloadPlugin", parameterTypes = emptyList())?.let { method ->
            method.hook {
                before { param ->
                    HookFailurePolicy.open("SystemUIPlugin", "unloadPlugin", Unit) {
                        val pluginInstance = param.thisObject
                        activeAodSessions.remove(pluginInstance)?.let { session ->
                            runCatching { session.hooker.prepareForHotReload() }
                            detach(session.hooker)
                        }
                        val session = activeSessions.release(pluginInstance)
                        if (session != null) {
                            runCatching { session.sliderPercentHooker.prepareForHotReload() }
                            runCatching { session.cornerHooker.prepareForHotReload() }
                            session.cardsEditHooker?.let { cardsEdit ->
                                runCatching { cardsEdit.prepareForHotReload() }
                                detach(cardsEdit)
                            }
                            session.cardResizeHooker?.let { resize ->
                                runCatching { resize.prepareForHotReload() }
                                detach(resize)
                            }
                            session.mediaDeviceIconHooker?.let { deviceIcon ->
                                runCatching { deviceIcon.prepareForHotReload() }
                                detach(deviceIcon)
                            }
                            session.mediaSuperIslandWhitelistHooker.let { mediaWhitelist ->
                                runCatching { mediaWhitelist.prepareForHotReload() }
                                detach(mediaWhitelist)
                            }
                            runCatching { session.threeIslandHooker.prepareForHotReload() }
                            detach(session.threeIslandHooker)
                            runCatching { session.liveUpdateIconHooker.prepareForHotReload() }
                            detach(session.liveUpdateIconHooker)
                            runCatching { session.timeFontHooker.prepareForHotReload() }
                            detach(session.timeFontHooker)
                            runCatching { session.superIslandHooker.prepareForHotReload() }
                            detach(session.superIslandHooker)
                            runCatching { session.volumeKeyHooker.prepareForHotReload() }
                            detach(session.volumeKeyHooker)
                            runCatching { session.tileMessageHooker.prepareForHotReload() }
                            detach(session.tileMessageHooker)
                            detach(session.sliderPercentHooker)
                            detach(session.cornerHooker)
                        }
                    }
                }
            }
        } ?: run {
            DebugLog.hookSkipped("SystemUIPlugin", "PluginInstance#unloadPlugin", "method not found")
        }
    }

    fun snapshotHotReloadPlugins(): List<HotReloadPluginState> {
        return activeSessions.states() + activeAodSessions.values.map { it.state }
    }

    fun restoreHotReloadPlugins(states: List<HotReloadPluginState>) {
        states.forEach { state ->
            runCatching {
                if (isAodPlugin(state.componentPackage, state.componentClass)) {
                    attachAodPluginHooker(state)
                } else {
                    attachPluginHooker(state)
                }
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
        DebugLog.d(
            "SystemUIPlugin",
            "SystemUIPluginHooker: loaded component=$componentName " +
                "plugin=${plugin?.javaClass?.name} loader=${classLoader?.javaClass?.name}"
        )
        if (classLoader == null) {
            DebugLog.hookFailed("SystemUIPlugin", "PluginInstance#loadPlugin classLoader", null)
            return
        }
        if (isControlCenterPlugin(componentName)) {
            runCatching {
                classLoader.loadClass("miui.systemui.controlcenter.panel.main.recyclerview.MainPanelAdapter")
                DebugLog.d("SystemUIPlugin", "plugin MainPanelAdapter resolved")
            }.onFailure { t ->
                DebugLog.w("SystemUIPlugin", "plugin MainPanelAdapter unresolved", t)
            }
        }

        val pluginFactory = readPluginField(pluginInstance, "mPluginFactory", "pluginFactory")
        val mAppInfo = pluginFactory?.let { readPluginField(it, "mAppInfo", "pluginAppInfo") as? ApplicationInfo }
        val state = HotReloadPluginState(
            pluginInstance = pluginInstance,
            componentPackage = componentName.packageName,
            componentClass = componentName.className,
            classLoader = classLoader,
            appContext = appContext,
            pluginApkPath = mAppInfo?.sourceDir ?: ""
        )
        if (isAodPlugin(componentName)) attachAodPluginHooker(state)
        else attachPluginHooker(state)
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

    private fun createPluginSession(state: HotReloadPluginState): PluginHookSession {
        val hooker = if (state.appContext != null && state.pluginApkPath.isNotEmpty()) {
            SliderPercentageHooker(state.appContext, state.pluginApkPath)
        } else {
            DebugLog.w("SystemUIPlugin", "Missing context or APK paths, instantiating with default fallback")
            SliderPercentageHooker()
        }
        val cornerHooker = if (state.appContext != null && state.pluginApkPath.isNotEmpty()) {
            ControlCenterCornerHooker(state.appContext, state.pluginApkPath)
        } else {
            ControlCenterCornerHooker()
        }
        val volumeKeyHooker = ControlCenterVolumeKeyHooker()
        val superIslandHooker = ControlCenterSuperIslandHooker()
        val threeIslandHooker = ThreeIslandHooker(state.pluginApkPath, classLoader)
        val liveUpdateIconHooker = LiveUpdateIconHooker()
        val timeFontHooker = IslandTimeFontHooker()
        val mediaSuperIslandWhitelistHooker = MediaSuperIslandWhitelistHooker()

        // The optional sub-hookers are created only when their switch is already on at session
        // creation; [ensureOptionalHookers] attaches the missing ones on the first live enable.
        return PluginHookSession(
                sliderPercentHooker = hooker,
                cornerHooker = cornerHooker,
                volumeKeyHooker = volumeKeyHooker,
                tileMessageHooker = TileStateMessageHooker(),
                superIslandHooker = superIslandHooker,
                threeIslandHooker = threeIslandHooker,
                liveUpdateIconHooker = liveUpdateIconHooker,
                timeFontHooker = timeFontHooker,
                mediaSuperIslandWhitelistHooker = mediaSuperIslandWhitelistHooker,
                classLoader = state.classLoader
            ).also { session ->
                if (Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED, false)) {
                    session.cardsEditHooker = ControlCenterCardsEditHooker()
                }
                if (Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_RESIZE_ENABLED, false)) {
                    session.cardResizeHooker = ControlCenterCardResizeHooker()
                }
                if (Preferences.getBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, false)) {
                    session.mediaDeviceIconHooker = MediaPlayerDeviceIconHooker()
                }
            }
    }

    private fun attachPluginHooker(state: HotReloadPluginState) {
        val (session, created) = activeSessions.acquire(state.pluginInstance, state.classLoader, state) {
            createPluginSession(state)
        }
        if (!created) {
            DebugLog.d("SystemUIPlugin", "reused plugin hooks loader=${System.identityHashCode(state.classLoader)} component=${state.componentClass}")
            return
        }
        val hooker = session.sliderPercentHooker
        val cornerHooker = session.cornerHooker
        val volumeKeyHooker = session.volumeKeyHooker
        val superIslandHooker = session.superIslandHooker
        val threeIslandHooker = session.threeIslandHooker
        val liveUpdateIconHooker = session.liveUpdateIconHooker
        val timeFontHooker = session.timeFontHooker
        val cardsEditHooker = session.cardsEditHooker
        val cardResizeHooker = session.cardResizeHooker
        val mediaDeviceIconHooker = session.mediaDeviceIconHooker
        val mediaSuperIslandWhitelistHooker = session.mediaSuperIslandWhitelistHooker
        val sharedSession = state.pluginApkPath.takeIf(String::isNotBlank)?.let(DexRuntime::beginSession)
        try {
            // Each attach is its own failure boundary: a throw from one hooker's onHook (e.g. a
            // DexKit scan hiccup) must not skip the other — that was silently leaving the corner
            // hooker uninstalled while the session entry suppressed later retries.
            runCatching { attach(threeIslandHooker, state.classLoader) }
                .onFailure { DebugLog.e("SystemUIPlugin", "failed to attach three-island scheduler", it) }
            runCatching { attach(timeFontHooker, state.classLoader) }
                .onFailure { DebugLog.e("SystemUIPlugin", "failed to attach island timer font", it) }
            runCatching { attach(liveUpdateIconHooker, state.classLoader) }
                .onFailure { DebugLog.e("SystemUIPlugin", "failed to attach live-update island icons", it) }
            runCatching { attach(superIslandHooker, state.classLoader) }
                .onFailure { DebugLog.e("SystemUIPlugin", "failed to attach Control Center Super Island hook", it) }
            runCatching { attach(hooker, state.classLoader) }
                .onFailure { t ->
                    DebugLog.e("SystemUIPlugin", "failed to attach slider-percent plugin hook", t)
                }
            runCatching { attach(cornerHooker, state.classLoader) }
                .onFailure { t ->
                    DebugLog.e("SystemUIPlugin", "failed to attach corner plugin hook", t)
                }
            runCatching { attach(session.tileMessageHooker, state.classLoader) }
                .onFailure { DebugLog.e("SystemUIPlugin", "failed to attach tile-message duration", it) }
            runCatching { attach(volumeKeyHooker, state.classLoader) }
                .onFailure { DebugLog.e("SystemUIPlugin", "failed to attach volume-key plugin hook", it) }
            if (cardsEditHooker != null) {
                runCatching { attach(cardsEditHooker, state.classLoader) }
                    .onFailure { t ->
                        DebugLog.e("SystemUIPlugin", "failed to attach cards-edit plugin hook", t)
                    }
            }
            if (cardResizeHooker != null) {
                runCatching { attach(cardResizeHooker, state.classLoader) }
                    .onFailure { t ->
                        DebugLog.e("SystemUIPlugin", "failed to attach card-resize plugin hook", t)
                    }
            }
            if (mediaDeviceIconHooker != null) {
                runCatching { attach(mediaDeviceIconHooker, state.classLoader) }
                    .onFailure { t ->
                        DebugLog.e("SystemUIPlugin", "failed to attach media-device-icon plugin hook", t)
                    }
            }
            run {
                runCatching { attach(mediaSuperIslandWhitelistHooker, state.classLoader) }
                    .onFailure { t ->
                        DebugLog.e("SystemUIPlugin", "failed to attach media Super Island plugin hook", t)
                    }
            }
        } finally { sharedSession?.close() }
        DebugLog.i("SystemUIPlugin", "attached shared plugin hooks loader=${System.identityHashCode(state.classLoader)} component=${state.componentClass}")
    }

    private fun attachAodPluginHooker(state: HotReloadPluginState) {
        if (activeAodSessions.containsKey(state.pluginInstance)) return
        val hooker = AodStatusIconHooker()
        runCatching { attach(hooker, state.classLoader) }
            .onSuccess {
                activeAodSessions[state.pluginInstance] = AodPluginSession(state, hooker)
                DebugLog.i("SystemUIPlugin", "attached AOD plugin ${state.componentClass}")
            }
            .onFailure { error ->
                detach(hooker)
                DebugLog.e("SystemUIPlugin", "failed to attach AOD plugin ${state.componentClass}", error)
            }
    }

    private fun isControlCenterPlugin(componentName: ComponentName): Boolean {
        return componentName.packageName == "miui.systemui.plugin" ||
            componentName.className == "miui.systemui.controlcenter.MiuiControlCenter"
    }

    private fun isAodPlugin(componentName: ComponentName): Boolean =
        isAodPlugin(componentName.packageName, componentName.className)

    private fun isAodPlugin(packageName: String?, className: String?): Boolean =
        packageName == "com.miui.aod" && className == "com.miui.aod.doze.DozeServicePluginImpl"
}
