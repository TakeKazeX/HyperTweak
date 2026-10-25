package com.takekazex.hypertweak.hook

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadHandleStore
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.ModuleContext
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import com.takekazex.hypertweak.hook.base.SettingsReloadDispatcher
import com.takekazex.hypertweak.hook.bootstrap.PackageHookPlan
import com.takekazex.hypertweak.hook.bootstrap.RuntimeSettingsParticipants
import com.takekazex.hypertweak.hook.bootstrap.SystemServerHookPlan
import com.takekazex.hypertweak.hook.rules.backgesture.AospBackSystemUiHooker
import com.takekazex.hypertweak.hook.rules.gms.QuickSharePhenotypeHooker
import com.takekazex.hypertweak.hook.rules.googleapp.GoogleAppProcessPolicy
import com.takekazex.hypertweak.hook.rules.googleapp.GoogleAppRuntime
import com.takekazex.hypertweak.hook.rules.module.RestartBroadcastHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.BatteryInfoHooker
import com.takekazex.hypertweak.hook.rules.system.BatteryAutoPowerSaveHooker
import com.takekazex.hypertweak.hook.rules.systemui.AODHooker
import com.takekazex.hypertweak.hook.rules.systemui.ExtendUnlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.HideBottomBarHooker
import com.takekazex.hypertweak.hook.rules.systemui.HideLockscreenStatusBarHooker
import com.takekazex.hypertweak.hook.rules.systemui.ProxyLaunchHooker
import com.takekazex.hypertweak.hook.rules.systemui.SystemUIPluginHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterCarrierBlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.LeftContainerHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.StackedSignalHooker
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.DebugLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.ApplicationAttachCallback
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class HookEntry : XposedModule() {
    private companion object {
        /**
         * The launcher process. It carries no dex of its own for the module's hooks, but it is in
         * the declared scope so LSPosed injects both the native payload (which owns the Dart
         * rules) and this module's Java (which is the only side able to read the preferences).
         */
        const val LAUNCHER_PACKAGE = "com.miui.home"

        /** Header clock seconds: owned by the header domain and applied by its own participant. */
        const val KEY_HEADER_CLOCK_SECONDS = "notification_header_clock_seconds"
    }

    /** Bounded retry window for the transient daemon remote-preferences outage. */
    private val MAX_PREFS_INIT_RETRIES = 4
    private val PREFS_RETRY_DELAY_MS = 500L

    private val injectedPackages = ConcurrentHashMap.newKeySet<String>()
    private val rootHookers = ConcurrentHashMap.newKeySet<BaseHooker>()
    private val packageStates = ConcurrentHashMap<String, HotReloadPackageState>()
    private val pendingAppContextPackages = ConcurrentHashMap.newKeySet<String>()
    private val preferenceRetryGeneration = AtomicLong(0L)
    @Volatile private var nativeRuleStatePublisher: NativeRuleStatePublisher? = null
    @Volatile private var generationRetired = false
    @Synchronized private fun nativePublisher(): NativeRuleStatePublisher? {
        if (generationRetired || processName != "com.android.systemui") return null
        return nativeRuleStatePublisher ?: NativeRuleStatePublisher().also { nativeRuleStatePublisher = it }
    }
    private val runtimeSettingsReceiver by lazy {
        RuntimeSettingsReceiver(object : (Set<String>, Set<String>) -> Unit {
            override fun invoke(groups: Set<String>, keys: Set<String>) = requestSettingsReload(groups, keys)
        })
    }
    private val systemUiSettings = SystemUiSettingsHost(
        { processName }, { generationRetired }, { packageStates["com.android.systemui"] }, { rootHookers }
    )
    private val iconSettingsLock = java.util.concurrent.locks.ReentrantLock()
    private val settingsHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val settingsReload by lazy {
        SettingsReloadDispatcher(
            post = { task, delay -> settingsHandler.postDelayed(task, delay) },
            remove = { settingsHandler.removeCallbacks(it) },
            failure = { id, error -> DebugLog.w("RuntimeSettings", "reload failed participant=$id", error) }
        ).apply {
            RuntimeSettingsParticipants.install(this, processName, isSystemServer,
                RuntimeSettingsParticipants.Actions(
                    icons = { keys ->
                        if (!iconSettingsLock.tryLock()) false else try { systemUiSettings.applyLiveIconSettings(keys) }
                        finally { iconSettingsLock.unlock() }
                    },
                    publishNative = { publishNativeRuleSwitches() },
                    aod = { systemUiSettings.applyLiveAodSettings() },
                    header = { systemUiSettings.applyHeaderSettings(it) },
                    notificationText = { systemUiSettings.applyNotificationTextSettings(it) },
                    hookers = { rootHookers.toList() },
                    retired = { generationRetired }
                )
            )
        }
    }
    private fun requestSettingsReload(groups: Set<String>, keys: Set<String> = emptySet()) {
        if (groups.isEmpty()) return
        settingsHandler.post {
            if (!generationRetired) settingsReload.request(
                groups.intersect(RuntimeSettingsGroups.forProcess(processName, isSystemServer)), keys
            )
        }
    }
    private fun cancelSettingsReload() {
        // API-102 preparation can run off-main; serialize retirement with live replacement.
        if (android.os.Looper.myLooper() == settingsHandler.looper) settingsReload.cancel() else {
            val done = java.util.concurrent.CountDownLatch(1)
            settingsHandler.post { try { settingsReload.cancel() } finally { done.countDown() } }
            check(done.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "settings reload retirement timed out" }
        }
    }
    private fun resumeSettingsReload() { settingsHandler.post { settingsReload.resume() } }

    private val runtimeContextRetry by lazy {
        com.takekazex.hypertweak.hook.rules.system.SystemServerStartupRetry("RuntimeSettings", action = {
            val owner = Class.forName("android.app.ActivityThread")
            val thread = owner.getMethod("currentActivityThread").invoke(null) ?: return@SystemServerStartupRetry false
            val context = owner.getMethod("getSystemContext").invoke(thread) as? Context ?: return@SystemServerStartupRetry false
            attachRuntimeSettingsContext(context)
            true
        })
    }
    private fun attachRuntimeSettingsContext(context: Context) {
        if (isSystemServer || processName == "com.android.systemui") {
            com.takekazex.hypertweak.util.UpstreamBackGesture.refresh(context)
        }
        if (!generationRetired && RuntimeSettingsGroups.forProcess(processName, isSystemServer).isNotEmpty()) runtimeSettingsReceiver.attach(context)
    }

    @Volatile private var runtimePreferencesSource: SharedPreferences? = null
    @Volatile private var remoteSettingsReady = false
    private val runtimePreferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!generationRetired) {
            Preferences.invalidateRuntimeReadCache()
            // The daemon rewrites complete subsets (remove + put), and may report null or
            // unchanged keys. Only the acknowledged snapshot diff owns feature recreation.
            if (key == null || key == RuntimeSettingsGroups.REVISION || RuntimeSettingsGroups.owns(key))
                runtimeSettingsReceiver.resync()
        }
    }

    private fun detachRuntimePreferencesListener() {
        runtimePreferencesSource?.let { source ->
            runCatching { source.unregisterOnSharedPreferenceChangeListener(runtimePreferencesListener) }
        }
        runtimePreferencesSource = null
    }

    private fun bindRuntimePreferencesListener(source: SharedPreferences) {
        if (RuntimeSettingsGroups.forProcess(processName, isSystemServer).isEmpty() || runtimePreferencesSource === source) return
        detachRuntimePreferencesListener()
        runCatching {
            source.registerOnSharedPreferenceChangeListener(runtimePreferencesListener)
            runtimePreferencesSource = source
        }.onFailure { DebugLog.w("NativeRules", "runtime settings listener unavailable", it) }
    }
    private var skipGoogleProcess = false
    private lateinit var processName: String
    private var isSystemServer: Boolean = false
    private var systemServerClassLoader: ClassLoader? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        // Process boundary. The process identity is recorded unconditionally (later callbacks and
        // the debug log need it), then everything else runs behind a boundary: a module failure
        // here must be logged, not thrown back into the host's module-load path.
        processName = param.processName
        isSystemServer = param.isSystemServer
        skipGoogleProcess = !isSystemServer && GoogleAppProcessPolicy.shouldSkipProcess(processName)
        if (skipGoogleProcess) return
        RuntimeSettingsStore.configure(processName, isSystemServer)
        try {
            preferenceRetryGeneration.incrementAndGet()
            // Initialize EzXposed with the module interface
            EzXposed.initOnModuleLoaded(this, param)
            NativeRules.bindProcess(processName)
            DebugLog.setProcessTag(processName)
            DebugLog.bindXposed(this)
            initPreferences()
            DebugLog.ensureSession()
            DebugLog.d("HookEntry", "module loaded process=$processName isSystemServer=$isSystemServer")
            // Status inspection is passive; only launcher rule publication may bind the JNI bridge.
            DebugLog.d("NativeRules", NativeRules.describe())
            publishNativeRuleSwitches()
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "module load handling failed", t)
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        EzXposed.initOnSystemServerStarting(param)
        systemServerClassLoader = param.classLoader
        DebugLog.d("HookEntry", "system_server starting")
        // Safety net: a structural failure while dispatching the framework hooks (any hooker failing
        // to attach, or the dispatch itself blowing up) must land in the in-app debug log so it can
        // be diagnosed from the app's Logs page without digging through LSPosed's daemon files.
        // Each attachHooker already isolates per-hooker failures; this catches anything that escapes
        // it (e.g. an unguarded preference read) and records it, then rethrows so the host still
        // sees the failure.
        try {
            dispatchSystemServerHookers(param.classLoader)
            runtimeContextRetry.schedule()
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "system_server hook dispatch failed", t)
            throw t
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (skipGoogleProcess) return
        if (!injectedPackages.add(param.packageName)) return
        // Process boundary: a failure while installing this package's hooks must be isolated and
        // logged rather than escaping into the host app's own package-load path.
        try {
            EzXposed.initOnPackageLoaded(param)
            EzReflect.init(param.defaultClassLoader)
            recordPackageState(
                packageName = param.packageName,
                classLoader = param.defaultClassLoader,
                appInfo = param.applicationInfo,
                isFirstPackage = param.isFirstPackage,
                isPackageReady = false,
                appContext = null
            )
            DebugLog.d(
                "HookEntry",
                "package loaded package=${param.packageName} process=$processName first=${param.isFirstPackage}"
            )

            dispatchPackageHookers(
                packageName = param.packageName,
                classLoader = param.defaultClassLoader,
                appInfo = param.applicationInfo,
                isFirstPackage = param.isFirstPackage
            )
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "package load handling failed package=${param.packageName}", t)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (skipGoogleProcess) return
        // Process boundary, same rationale as onPackageLoaded.
        try {
            // Establishes the target snapshot required for hot reload state restore.
            EzXposed.initOnPackageReady(param)
            recordPackageState(
                packageName = param.packageName,
                classLoader = param.classLoader,
                appInfo = param.applicationInfo,
                isFirstPackage = false,
                isPackageReady = true,
                appContext = runCatching { EzXposed.appContextOrNull }.getOrNull(),
                pluginStates = currentPluginStates(param.packageName)
            )

            handlePackageReadyContext(param.packageName, param.classLoader)
            if (processName == LAUNCHER_PACKAGE) {
                // The early daemon binding may have failed before package readiness.
                if (runtimePreferencesSource == null) tryInitPreferences()
                publishNativeRuleSwitches()
            }

            if (param.packageName == "com.android.systemui") {
                val appContext = packageStates[param.packageName]?.appContext
                    ?: runCatching { EzXposed.appContextOrNull }.getOrNull()
                HideBottomBarHooker.onPackageReady(appContext, param.classLoader)
                // The launcher Overview bridge needs an application context, which package load
                // does not have yet. Without this the authoritative recent-tasks exit signal is
                // never registered and the gesture bar falls back to a polling watchdog.
                AospBackSystemUiHooker.onApplicationContextReady(appContext)
            }
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "package ready handling failed package=${param.packageName}", t)
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        if (skipGoogleProcess) return true
        preferenceRetryGeneration.incrementAndGet()
        DebugLog.d(
            "HookEntry",
            "hot reloading old generation process=$processName packages=${packageStates.size} roots=${rootHookers.size} modes=${hotReloadModeSummary()}"
        )
        if (com.takekazex.hypertweak.hook.base.dex.DexRuntime.isResolving) {
            DebugLog.w(
                "HookEntry",
                "deferred hot reload during active DEX resolution process=$processName"
            )
            return false
        }
        iconSettingsLock.lock()
        val ready = try { runCatching {
            if (RuntimeSettingsGroups.forProcess(processName, isSystemServer).isNotEmpty()) cancelSettingsReload()
            AospBackSystemUiHooker.checkHotReloadReady()
            refreshHotReloadSnapshots()
            if (!DexKitManager.prepareForHotReload()) {
                error("DexKit native bridge users are still active")
            }
            val hyperTweakState = HotReloadState.save(
                processName = processName,
                isSystemServer = isSystemServer,
                systemServerClassLoader = systemServerClassLoader,
                packages = packageStates.values,
                hookerStates = rootHookers.associate { it.hookerName to it.saveHotReloadState() } +
                    mapOf("HyperTweak.NativeSettings" to nativeRuleStatePublisher?.saveSnapshot(),
                        "HyperTweak.RuntimeSettings" to RuntimeSettingsStore.save())
            )
            if (processName == "com.android.systemui") {
                com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHotReloadRecovery.cancel()
            }
            rootHookers.forEach { it.prepareForHotReload() }
            rootHookers.forEach { it.resetAfterHotReloadPrepared() }
            rootHookers.clear()
            param.setSavedInstanceState(hyperTweakState)
            DebugLog.d("HookEntry", "hot reload preparation completed; old generation can retire")
            detachRuntimePreferencesListener()
            remoteSettingsReady = false
            generationRetired = true
            if (RuntimeSettingsGroups.forProcess(processName, isSystemServer).isNotEmpty()) runtimeSettingsReceiver.close()
            RuntimeSettingsPreferences.clearObservers()
            runtimeContextRetry.cancel()
            nativeRuleStatePublisher?.close()
            nativeRuleStatePublisher = null
            DebugLog.prepareForHotReload()
        }.onFailure { t ->
            DexKitManager.cancelHotReloadPreparation()
            DebugLog.e("HookEntry", "hot reload preparation failed; keeping old generation active", t)
            if (RuntimeSettingsGroups.forProcess(processName, isSystemServer).isNotEmpty()) resumeSettingsReload()
        }.isSuccess } finally { iconSettingsLock.unlock() }
        return ready
    }

    private fun refreshHotReloadSnapshots() {
        packageStates["com.android.systemui"]?.let { state ->
            packageStates["com.android.systemui"] = state.copy(
                pluginStates = SystemUIPluginHooker.snapshotHotReloadPlugins()
            )
        }
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        processName = param.processName
        isSystemServer = param.isSystemServer
        skipGoogleProcess = !isSystemServer && GoogleAppProcessPolicy.shouldSkipProcess(processName)
        if (skipGoogleProcess) {
            // An upgrade may carry handles from a version that hooked all Google processes.
            // Retire them without restoring package state, preference listeners or DEX workers.
            if (param.oldHookHandles.isNotEmpty()) {
                DebugLog.setProcessTag(processName)
                DebugLog.bindXposed(this)
                unhookRemainingOldHandles(HotReloadHandleStore(param.oldHookHandles))
                DebugLog.prepareForHotReload()
            }
            return
        }
        RuntimeSettingsStore.configure(processName, isSystemServer)
        generationRetired = false
        if (RuntimeSettingsGroups.forProcess(processName, isSystemServer).isNotEmpty()) cancelSettingsReload()
        preferenceRetryGeneration.incrementAndGet()
        EzXposed.initOnModuleLoaded(this, param)
        NativeRules.bindProcess(processName)
        DebugLog.setProcessTag(processName)
        DebugLog.bindXposed(this)
        val restoredState = HotReloadState.restore(param.savedInstanceState) ?: recoverSecurityHostState()
        nativePublisher()?.restoreSnapshot(restoredState?.hookerStates?.get("HyperTweak.NativeSettings"))
        RuntimeSettingsStore.restore(restoredState?.hookerStates?.get("HyperTweak.RuntimeSettings")
            ?: (restoredState?.hookerStates?.get("HyperTweak.NativeSettings") as? Array<*>)?.getOrNull(2))
        initPreferences()
        publishNativeRuleSwitches()
        DebugLog.ensureSession()
        val oldHandles = HotReloadHandleStore(param.oldHookHandles)
        val oldHandleIds = oldHandles.ids
        DebugLog.d(
            "HookEntry",
            "hot reloaded process=$processName packages=${restoredState?.packages?.map { it.packageName }} oldHandles=${oldHandles.totalCount} oldIds=${oldHandles.idCount} unnamed=${oldHandles.unnamedCount} duplicateIds=${oldHandles.duplicateIdCount}"
        )

        injectedPackages.clear()
        rootHookers.clear()
        packageStates.clear()
        pendingAppContextPackages.clear()

        if (restoredState == null) {
            DebugLog.w("HookEntry", "hot reloaded without restorable target state")
            unhookRemainingOldHandles(oldHandles)
            return
        }

        // Match upstream's ordering: stable Google callbacks are attached to the carried old
        // executable before the new generation starts its DexKit pass. Retired investigation IDs
        // are neutralized here as well, so they cannot keep stale behavior alive after an update.
        val googleProcess = processName == GoogleAppRuntime.PACKAGE ||
            processName.startsWith("${GoogleAppRuntime.PACKAGE}:") ||
            restoredState.packages.any { it.packageName == GoogleAppRuntime.PACKAGE }
        var googleReplaced = 0
        if (googleProcess) {
            oldHandles.remainingHandles().forEach { handle ->
                if (GoogleAppRuntime.replaceOldHandle(handle)) {
                    oldHandles.markHandled(handle)
                    googleReplaced++
                }
            }
        }
        if (googleReplaced > 0) {
            DebugLog.d("HookEntry", "replaced carried Google hooks=$googleReplaced")
        }

        processName = restoredState.processName
        isSystemServer = restoredState.isSystemServer
        systemServerClassLoader = restoredState.systemServerClassLoader

        if (restoredState.isSystemServer) {
            val targetClassLoader = restoredState.systemServerClassLoader ?: run {
                DebugLog.w("HookEntry", "hot reloaded system_server without classLoader")
                unhookRemainingOldHandles(oldHandles)
                return
            }
            EzReflect.init(targetClassLoader)
            dispatchSystemServerHookers(targetClassLoader, oldHandles)
        } else {
            restoredState.packages.forEach { state ->
                recordPackageState(
                    packageName = state.packageName,
                    classLoader = state.classLoader,
                    appInfo = state.appInfo,
                    isFirstPackage = state.isFirstPackage,
                    isPackageReady = state.isPackageReady,
                    appContext = state.appContext,
                    pluginStates = state.pluginStates
                )
                injectedPackages.add(state.packageName)
                if (state.appContext != null) {
                    pendingAppContextPackages.remove(state.packageName)
                }
                EzReflect.init(state.classLoader)
                dispatchPackageHookers(
                    packageName = state.packageName,
                    classLoader = state.classLoader,
                    appInfo = state.appInfo,
                    isFirstPackage = false,
                    isPackageReady = state.isPackageReady,
                    replacementHandles = oldHandles
                )
                if (state.isPackageReady) {
                    onRestoredPackageReady(state, oldHandles)
                }
            }
        }

        restoreHookerStates(restoredState.hookerStates)

        logHotReloadHandleDiff(oldHandleIds, oldHandles)
        unhookRemainingOldHandles(oldHandles)
        retryHookersAfterHotReload()
        if (isSystemServer) {
            runtimeContextRetry.schedule()
            com.takekazex.hypertweak.hook.rules.system.DockBackgroundHooker.recoverExistingWindows()
        }
        // Publication requires the restored host context. The early hot-reload callback runs
        // before package snapshots are restored and cannot be its only recovery boundary.
        publishNativeRuleSwitches()
        if (!isSystemServer && processName == "com.android.systemui") {
            val context = restoredState.packages.firstOrNull { it.packageName == processName }?.appContext
            if (context != null) {
                com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHotReloadRecovery.schedule(context)
            } else {
                DebugLog.w("HookEntry", "SystemUI hot reload recovery missing application context")
            }
        }
        if (RuntimeSettingsGroups.forProcess(processName, isSystemServer).isNotEmpty()) resumeSettingsReload()
        DebugLog.i("HookEntry", "hot reload replacement ready process=$processName roots=${rootHookers.size}")
    }

    /** An older module may have no package snapshot; recover only an already living verified host. */
    private fun recoverSecurityHostState(): HotReloadTargetState? {
        val owner = SecurityHostRecovery.owner(processName, isSystemServer) ?: return null
        return runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.app.Application
                ?: return@runCatching null
            val info = app.applicationInfo
            if (app.packageName != owner || info.packageName != owner ||
                info.uid != android.os.Process.myUid() || info.sourceDir.isNullOrBlank()
            ) return@runCatching null
            DebugLog.i("HookEntry", "recovering live security host without prior state package=$owner process=$processName")
            HotReloadTargetState(
                processName = processName,
                isSystemServer = false,
                systemServerClassLoader = null,
                packages = listOf(HotReloadPackageState(
                    packageName = owner,
                    processName = processName,
                    classLoader = app.classLoader,
                    appInfo = info,
                    isFirstPackage = true,
                    isPackageReady = true,
                    appContext = app,
                )),
            )
        }.onFailure { DebugLog.w("HookEntry", "state-free security host recovery failed process=$processName", it) }
            .getOrNull()
    }

    /** Recover a hooker whose in-place replacement failed without restarting the host process. */
    private fun retryHookersAfterHotReload() {
        rootHookers.toList().forEach { it.retryHookIfNeeded() }
    }

    private fun logHotReloadHandleDiff(
        oldHandleIds: Set<String>,
        oldHandles: HotReloadHandleStore
    ) {
        val newHandleIds = rootHookers.flatMap { it.collectManagedHookHandles() }
            .mapNotNull { it.id }
            .toSet()
        val replacedCount = oldHandles.totalCount - oldHandles.remainingCount
        DebugLog.d(
            "HookEntry",
            "hot reload registered new handles=${newHandleIds.size} replaced=$replacedCount matched=${newHandleIds.intersect(oldHandleIds).size} added=${newHandleIds.minus(oldHandleIds).size} remainingOld=${oldHandles.remainingCount}"
        )
    }

    private fun unhookRemainingOldHandles(handles: HotReloadHandleStore) {
        var unhookedCount = 0
        var unhookFailedCount = 0
        handles.remainingHandles().forEach { handle ->
            runCatching {
                handle.unhook()
                handles.markHandled(handle)
                unhookedCount++
            }.onFailure {
                unhookFailedCount++
                DebugLog.w("HookEntry", "failed to unhook old handle ${handle.id}", it)
            }
        }
        DebugLog.d(
            "HookEntry",
            "hot reload removed unmatched old handles ok=$unhookedCount failed=$unhookFailedCount"
        )
    }

    private fun restoreHookerStates(states: Map<String, Any?>) {
        com.takekazex.hypertweak.hook.base.HotReloadHostRecovery.run(
            rootHookers.toList(), states, { it.hookerName },
            { hooker, state -> hooker.restoreHotReloadState(state) },
            { it.recoverHotReloadHosts() },
            { hooker, error -> DebugLog.e("HookEntry", "failed to restore/recover ${hooker.hookerName}", error) }
        )
    }

    private fun recordPackageState(
        packageName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo?,
        isFirstPackage: Boolean,
        isPackageReady: Boolean,
        appContext: Context?,
        pluginStates: List<HotReloadPluginState> = emptyList()
    ) {
        val old = packageStates[packageName]
        packageStates[packageName] = HotReloadPackageState(
            packageName = packageName,
            processName = processName,
            classLoader = classLoader,
            appInfo = appInfo ?: old?.appInfo,
            isFirstPackage = old?.isFirstPackage ?: isFirstPackage,
            isPackageReady = old?.isPackageReady == true || isPackageReady,
            appContext = appContext ?: old?.appContext,
            pluginStates = if (pluginStates.isNotEmpty()) pluginStates else old?.pluginStates.orEmpty()
        )
        if (packageStates[packageName]?.appContext != null) {
            pendingAppContextPackages.remove(packageName)
        }
    }

    private fun handlePackageReadyContext(packageName: String, classLoader: ClassLoader) {
        val appContext = packageStates[packageName]?.appContext ?: runCatching {
            EzXposed.appContextOrNull
        }.getOrNull()?.also { context ->
            recordPackageState(
                packageName = packageName,
                classLoader = classLoader,
                appInfo = packageStates[packageName]?.appInfo,
                isFirstPackage = false,
                isPackageReady = true,
                appContext = context,
                pluginStates = currentPluginStates(packageName)
            )
        }

        if (appContext != null) {
            onPackageReadyContextAvailable(packageName, appContext)
            return
        }

        DebugLog.d("HookEntry", "package ready package=$packageName waiting for app context")
        if (!pendingAppContextPackages.add(packageName)) return

        EzXposed.runOnApplicationAttach(object : ApplicationAttachCallback {
            override fun onApplicationAttached(context: Context) {
                val appContext = context.applicationContext ?: context
                if (appContext.packageName != packageName) return
                if (!pendingAppContextPackages.remove(packageName)) return

                recordPackageState(
                    packageName = packageName,
                    classLoader = classLoader,
                    appInfo = packageStates[packageName]?.appInfo,
                    isFirstPackage = false,
                    isPackageReady = true,
                    appContext = appContext,
                    pluginStates = currentPluginStates(packageName)
                )
                onPackageReadyContextAvailable(packageName, appContext)
            }
        })
    }

    private fun onPackageReadyContextAvailable(packageName: String, appContext: Context) {
        Preferences.initLocalCache(appContext)
        RestartBroadcastHooker.register(appContext)
        attachRuntimeSettingsContext(appContext)
        if (packageName == BatteryInfoHooker.PACKAGE && processName == packageName) {
            BatteryInfoHooker.onPackageReady(appContext)
        }
        if (packageName == "com.android.systemui") {
            onSystemUiContextReady(appContext)
        }
        if (packageName == "com.google.android.gms") {
            QuickSharePhenotypeHooker.onPackageReady(appContext)
        }
        DebugLog.d("HookEntry", "package ready package=$packageName context=${appContext.packageName}")
    }

    /** Cold startup and hot reload must publish the same host environment to every consumer. */
    private fun onSystemUiContextReady(context: Context) {
        AODHooker.refreshSettings()
        nativePublisher()?.attach(context)
        attachRuntimeSettingsContext(context)
        ProxyLaunchHooker.register(context)
        ExtendUnlockHooker.syncTrustAgent(context)
        StackedSignalHooker.onPackageReady(context)
        ControlCenterCarrierBlockHooker.onPackageReady(context)
        LeftContainerHooker.onPackageReady(context)
        // The battery monitor needs a Context to register its receiver; appContext is null while
        // hooks are attached at package load.
        BatteryAutoPowerSaveHooker.onPackageReady(context)
        // The launcher Overview bridge needs an application context, and this is the path that
        // actually runs for SystemUI: package load has no context, so the earlier registration
        // attempt inside installSystemUiHooks only logs and defers. Without this call the module
        // never receives the authoritative Overview enter/exit, and recent-task hiding degrades to
        // guessing from timers. Device logs on 2026-10-20 confirmed zero registrations.
        AospBackSystemUiHooker.onApplicationContextReady(context)
        // Readiness signal for the keyguard status bar discovery fallback; the hooker never treats
        // the context as a View.
        HideLockscreenStatusBarHooker.onApplicationContextReady(context)
    }

    private fun hotReloadModeSummary(): String {
        return HotReloadMode.entries.joinToString(prefix = "{", postfix = "}") { mode ->
            val names = rootHookers
                .filter { it.hotReloadMode == mode }
                .map { it.hookerName }
                .sorted()
            "${mode.name}=$names"
        }
    }

    private fun onRestoredPackageReady(
        state: HotReloadPackageState,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        val appContext = state.appContext ?: runCatching { EzXposed.appContextOrNull }.getOrNull()
        if (appContext != null) {
            Preferences.initLocalCache(appContext)
            RestartBroadcastHooker.register(appContext)
            attachRuntimeSettingsContext(appContext)
            if (state.packageName == BatteryInfoHooker.PACKAGE && processName == state.packageName) {
                BatteryInfoHooker.onPackageReady(appContext)
            }
            if (state.packageName == "com.android.systemui") {
                onSystemUiContextReady(appContext)
            }
            if (state.packageName == "com.google.android.gms") {
                QuickSharePhenotypeHooker.onPackageReady(appContext)
            }
            DebugLog.d("HookEntry", "restored package ready package=${state.packageName} context=${appContext.packageName}")
        } else {
            DebugLog.w("HookEntry", "restored package ready package=${state.packageName} without app context")
        }

        if (state.packageName == "com.android.systemui") {
            HideBottomBarHooker.setHotReloadReplacementHandles(replacementHandles)
            runCatching {
                HideBottomBarHooker.onPackageReady(appContext, state.classLoader)
            }.also {
                HideBottomBarHooker.setHotReloadReplacementHandles(null)
            }.onFailure { t ->
                DebugLog.e("HookEntry", "failed to restore SystemUI package ready hooks", t)
            }
            runCatching { AospBackSystemUiHooker.onApplicationContextReady(appContext) }
                .onFailure { t -> DebugLog.e("HookEntry", "failed to restore the launcher overview bridge", t) }
            SystemUIPluginHooker.setHotReloadReplacementHandles(replacementHandles)
            runCatching {
                SystemUIPluginHooker.restoreHotReloadPlugins(state.pluginStates)
            }.also {
                SystemUIPluginHooker.setHotReloadReplacementHandles(null)
            }.onFailure { t ->
                DebugLog.e("HookEntry", "failed to restore SystemUI plugin hooks", t)
            }
        }
    }

    private fun currentPluginStates(packageName: String): List<HotReloadPluginState> {
        return if (packageName == "com.android.systemui") {
            SystemUIPluginHooker.snapshotHotReloadPlugins()
        } else {
            emptyList()
        }
    }

    private fun initPreferences() {
        if (tryInitPreferences()) return
        val retryGeneration = preferenceRetryGeneration.get()
        // The LSPosed daemon's remote-preferences channel is unavailable (e.g. it returns
        // null / throws "Framework returns null"). Preferences is left uninitialized; getters
        // fall back to the per-process cache rather than silently reading defaults, and the
        // flag lets the UI surface the degraded state.
        //
        // The outage may be transient (the daemon not ready when this process loads, or the
        // daemon restarting), so retry with bounded backoff before giving up for this process
        // lifetime. Once the bridge recovers, hookers that skipped installation are retried
        // in-place instead of permanently retaining their default-off state.
        Thread({
            var attempt = 0
            while (attempt < MAX_PREFS_INIT_RETRIES) {
                runCatching { Thread.sleep(PREFS_RETRY_DELAY_MS * (1L shl attempt)) }
                if (preferenceRetryGeneration.get() != retryGeneration) return@Thread
                if (tryInitPreferences()) {
                    retryPreferenceGatedHookers(retryGeneration)
                    publishNativeRuleSwitches()
                    break
                }
                attempt++
            }
        }, "HyperTweak-PrefsRetry").apply { isDaemon = true }.start()
    }

    /** Re-run only hookers that had no installed handles during the transient prefs outage. */
    private fun retryPreferenceGatedHookers(retryGeneration: Long) {
        if (preferenceRetryGeneration.get() != retryGeneration) return
        rootHookers.toList().forEach { it.retryHookIfNeeded() }
    }

    /**
     * Hands the launcher-side rule switches to the payload, in the launcher process only.
     *
     * `NativeRuleConfig` publishes the same values as a file in the module's shared media
     * directory, but that file is unreadable from here: the launcher is a `platform_app_36`
     * process with an ordinary app uid, is neither the file's owner nor in its `media_rw` group,
     * and scoped storage refuses it. The module's Java is injected into the launcher as well, so
     * this call is what actually reaches the rules — see [NativeRules.applyRuleSwitches].
     */
    private fun publishNativeRuleSwitches() {
        // An unavailable daemon must not overwrite the last good native snapshot
        // with default values during boot. The successful bind/retry publishes it.
        if (generationRetired) return
        if (processName == "com.android.systemui") {
            val context = packageStates[processName]?.appContext
                ?: runCatching { EzXposed.appContextOrNull }.getOrNull()
            val publisher = nativePublisher() ?: return
            if (context != null) { publisher.attach(context); runtimeSettingsReceiver.attach(context) }
            publisher.publish()
            return
        }
        if ((!remoteSettingsReady && !RuntimeSettingsStore.owns(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION)) || processName != LAUNCHER_PACKAGE) return
        runCatching {
            NativeRules.applyRuleSwitches(
                hideRecentsClearButton = Preferences.hideRecentsClearButton(),
                openedFolderColumns = Preferences.openedFolderColumns(),
                contextualSearchLongPress = Preferences.contextualSearchLongPress(),
                assistantWidgets = Preferences.allowAndroidWidgetsToAssistant()
            )
        }.onFailure { t ->
            DebugLog.w("HookEntry", "native rule switch publish failed", t)
        }
    }

    /** Runs one attempt at binding the daemon's remote preferences; true on success. */
    private fun tryInitPreferences(): Boolean {
        return try {
            val remotePrefs = getRemotePreferences(AppSettingsKeys.NAME)
            Preferences.init(remotePrefs)
            bindRuntimePreferencesListener(remotePrefs)
            requestSettingsReload(RuntimeSettingsGroups.forProcess(processName, isSystemServer))
            DebugLog.d("HookEntry", "processName=$processName loaded remotePrefs keys=${remotePrefs.all.keys}")
            remoteSettingsReady = true
            true
        } catch (t: Throwable) {
            remoteSettingsReady = false
            Preferences.noteRemoteBackendUnavailable()
            DebugLog.e("HookEntry", "failed to init Preferences (remote channel unavailable; using cache fallback)", t)
            false
        }
    }

    private fun dispatchSystemServerHookers(
        classLoader: ClassLoader,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        val ctx = ModuleContext(
            processName = processName,
            packageName = "system",
            isSystemServer = true,
            appContext = null
        )
        SystemServerHookPlan.attach { hooker ->
            attachHooker(hooker, classLoader, ctx, replacementHandles)
        }
    }

    private fun dispatchPackageHookers(
        packageName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo?,
        isFirstPackage: Boolean,
        isPackageReady: Boolean = false,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        val ctx = ModuleContext(
            processName = processName,
            packageName = packageName,
            isSystemServer = isSystemServer,
            isFirstPackage = isFirstPackage,
            isPackageReady = isPackageReady,
            appInfo = appInfo,
            appContext = null
        )

        com.takekazex.hypertweak.hook.base.dex.DexRuntime.registerSource(appInfo)
        // One lazy DexKit scope per package dispatch: every consumer below shares one bridge.
        val sharedSession = appInfo?.sourceDir?.takeIf { it.isNotBlank() }
            ?.let(com.takekazex.hypertweak.hook.base.dex.DexRuntime::beginSession)
        try {

        PackageHookPlan.attach(ctx, installIcons = {
            systemUiSettings.iconHookers().forEach { attachHooker(it, classLoader, ctx, replacementHandles) }
            // Compare the first full boot snapshot with the values used at installation.
            systemUiSettings.recordInstalledIcons()
        }) { hooker ->
            attachHooker(hooker, classLoader, ctx, replacementHandles)
        }
        } finally {
            sharedSession?.close()
        }
    }

    private fun attachHooker(
        hooker: BaseHooker,
        targetClassLoader: ClassLoader,
        ctx: ModuleContext,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        try {
            DebugLog.d("HookEntry", "attaching ${hooker::class.java.simpleName} package=${ctx.packageName}")
            hooker.module = this
            hooker.classLoader = targetClassLoader
            hooker.hookParam = ctx
            hooker.setHotReloadReplacementHandles(replacementHandles)

            rootHookers.add(hooker)
            hooker.performInit()
            hooker.updateParentState(true)
            hooker.setHotReloadReplacementHandles(null)
        } catch (t: Throwable) {
            hooker.setHotReloadReplacementHandles(null)
            DebugLog.e("HookEntry", "failed to attach hooker: ${hooker::class.java.simpleName}", t)
        }
    }
}
