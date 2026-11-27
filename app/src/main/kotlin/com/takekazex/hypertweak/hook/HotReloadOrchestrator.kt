package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.base.HostContext
import com.takekazex.hypertweak.hook.base.HotReloadHandleStore
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.bootstrap.AppHostRecovery
import com.takekazex.hypertweak.hook.bootstrap.ProcessLifecycleHooks
import com.takekazex.hypertweak.hook.bootstrap.ProcessLogging
import com.takekazex.hypertweak.hook.preferences.RuntimeSettingsPreferences
import com.takekazex.hypertweak.hook.runtime.reload.GenerationRetirement
import com.takekazex.hypertweak.hook.runtime.reload.HotReloadPackageState
import com.takekazex.hypertweak.hook.runtime.reload.HotReloadState
import com.takekazex.hypertweak.hook.runtime.reload.HotReloadTargetState
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsStore
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * API-102 hot reload for one module generation: [retire] runs in the old generation's
 * `onHotReloading`, [adopt] in the new generation's `onHotReloaded`. Retirement saves the
 * recovery payload before any destructive step; adoption restores package state, replaces
 * carried handles in place and unhooks what no new hook claimed.
 *
 * Owned by [HookEntry] per generation. [module] must be that [HookEntry] instance: icon ports,
 * host context and hooker registration are keyed by it.
 */
internal class HotReloadOrchestrator(
    private val module: XposedModule,
    private val state: HookProcessState,
    private val settings: ProcessSettings,
    private val dispatcher: PackageHookDispatcher
) {
    private val processName get() = state.processName

    fun retire(param: XposedModuleInterface.HotReloadingParam): Boolean {
        if (state.skipGoogleProcess) {
            param.setSavedInstanceState(GenerationRetirement.retireSkipped())
            return true
        }
        DebugLog.d(
            "HookEntry",
            "hot reloading old generation process=$processName packages=${state.packageStates.size} roots=${state.rootHookers.size}"
        )
        if (DexRuntime.isResolving) {
            DebugLog.w(
                "HookEntry",
                "deferred hot reload during active DEX resolution process=$processName"
            )
            return false
        }
        settings.iconSettingsLock.lock()
        val ready = try { com.takekazex.hypertweak.hook.base.HotReloadRetirement.run(
            preflight = {
                if (state.ownsRuntimeSettings) settings.cancelReload()
                ProcessLifecycleHooks.checkHotReloadReady()
                refreshHotReloadSnapshots()
                check(DexRuntime.prepareForRetirement()) { "DexKit native bridge users are still active" }
                val roots = state.rootHookers.toList()
                val saved = HotReloadState.save(
                    processName = processName,
                    isSystemServer = state.isSystemServer,
                    systemServerClassLoader = state.systemServerClassLoader,
                    packages = state.packageStates.values,
                    hookerStates = roots.associate { it.hookerName to it.saveHotReloadState() } +
                        mapOf("HyperTweak.NativeSettings" to settings.saveNativeSnapshot(),
                            "HyperTweak.RuntimeSettings" to RuntimeSettingsStore.save(),
                            GenerationRetirement.savedState())
                )
                // The framework must accept the recovery payload before any host is retired.
                param.setSavedInstanceState(saved)
                buildList<() -> Unit> {
                    add { state.generationRetired = true; settings.markRetired() }
                    if (processName == "com.android.systemui") add {
                        ProcessLifecycleHooks.cancelSystemUiRecovery()
                    }
                    roots.forEach { root -> add { root.prepareForHotReload() } }
                    roots.forEach { root -> add { root.resetAfterHotReloadPrepared() } }
                    add { state.rootHookers.clear() }
                    add { ProcessLifecycleHooks.retireRestartReceiver() }
                    if (processName.substringBefore(':') == "com.android.systemui") add {
                        com.takekazex.hypertweak.hook.bootstrap.SystemUiIconPorts.retire(module)
                    }
                    add { settings.detachPreferencesListener() }
                    if (state.ownsRuntimeSettings) add { settings.closeReceiver() }
                    add { RuntimeSettingsPreferences.clearObservers() }
                    add { settings.cancelContextRetry() }
                    add { settings.closeNativePublisher() }
                    // Last: process-wide steps that need every owner above already retired.
                    addAll(GenerationRetirement.finalSteps())
                }
            },
            rejected = { error ->
                DexRuntime.cancelRetirement()
                DebugLog.e("HookEntry", "hot reload preflight failed; old hooks untouched", error)
                if (state.ownsRuntimeSettings) settings.resumeReload()
            },
            cleanupFailed = { error ->
                DebugLog.e("HookEntry", "hot reload cleanup incomplete; accepting replacement for recovery", error)
            }
        ) } finally { settings.iconSettingsLock.unlock() }
        return ready
    }

    private fun refreshHotReloadSnapshots() {
        state.packageStates["com.android.systemui"]?.let { packageState ->
            state.packageStates["com.android.systemui"] = packageState.copy(
                pluginStates = ProcessLifecycleHooks.snapshotSystemUiPlugins()
            )
        }
    }

    fun adopt(param: XposedModuleInterface.HotReloadedParam) {
        state.processName = param.processName
        state.isSystemServer = param.isSystemServer
        state.skipGoogleProcess = !state.isSystemServer && ProcessLifecycleHooks.shouldSkipProcess(processName)
        if (state.skipGoogleProcess) {
            // An upgrade may carry handles from a version that hooked all Google processes.
            // Retire them without restoring package state, preference listeners or DEX workers.
            if (param.oldHookHandles.isNotEmpty()) {
                ProcessLogging.bind()
                DebugLog.setProcessTag(processName)
                DebugLog.bindXposed(module)
                unhookRemainingOldHandles(HotReloadHandleStore(param.oldHookHandles))
                DebugLog.prepareForHotReload()
            }
            GenerationRetirement.adoptSkipped(param.savedInstanceState)
            return
        }
        RuntimeSettingsStore.configure(processName, state.isSystemServer)
        state.generationRetired = false
        if (state.ownsRuntimeSettings) settings.cancelReload()
        settings.preferenceRetryGeneration.incrementAndGet()
        HostContext.reset(module)
        NativeRules.bindProcess(processName)
        ProcessLogging.bind()
        DebugLog.setProcessTag(processName)
        DebugLog.bindXposed(module)
        val savedState = HotReloadState.restore(param.savedInstanceState)
        val restoredState = when {
            savedState == null -> recoverAppHostState()
            !savedState.isSystemServer && savedState.packages.isEmpty() -> {
                val recovered = recoverAppHostState()
                if (recovered == null) savedState else savedState.copy(packages = recovered.packages)
            }
            else -> savedState
        }
        settings.restoreNativeSnapshot(restoredState?.hookerStates?.get("HyperTweak.NativeSettings"))
        RuntimeSettingsStore.restore(restoredState?.hookerStates?.get("HyperTweak.RuntimeSettings")
            ?: (restoredState?.hookerStates?.get("HyperTweak.NativeSettings") as? Array<*>)?.getOrNull(2))
        settings.initPreferences()
        settings.publishNativeRuleSwitches()
        DebugLog.ensureSession()
        GenerationRetirement.adopt(restoredState?.hookerStates)
        val oldHandles = HotReloadHandleStore(param.oldHookHandles)
        val oldHandleIds = oldHandles.ids
        DebugLog.d(
            "HookEntry",
            "hot reloaded process=$processName packages=${restoredState?.packages?.map { it.packageName }} oldHandles=${oldHandles.totalCount} oldIds=${oldHandles.idCount} unnamed=${oldHandles.unnamedCount} duplicateIds=${oldHandles.duplicateIdCount}"
        )

        state.injectedPackages.clear()
        state.rootHookers.clear()
        state.packageStates.clear()
        state.pendingAppContextPackages.clear()

        if (restoredState == null) {
            DebugLog.w("HookEntry", "hot reloaded without restorable target state")
            unhookRemainingOldHandles(oldHandles)
            return
        }

        // Match upstream's ordering: stable Google callbacks are attached to the carried old
        // executable before the new generation starts its DexKit pass. Retired investigation IDs
        // are neutralized here as well, so they cannot keep stale behavior alive after an update.
        val googleProcess = ProcessLifecycleHooks.isGoogleProcess(processName, restoredState.packages.map { it.packageName })
        var googleReplaced = 0
        if (googleProcess) {
            oldHandles.remainingHandles().forEach { handle ->
                if (ProcessLifecycleHooks.replaceCarriedGoogleHandle(handle)) {
                    oldHandles.markHandled(handle)
                    googleReplaced++
                }
            }
        }
        if (googleReplaced > 0) {
            DebugLog.d("HookEntry", "replaced carried Google hooks=$googleReplaced")
        }

        state.processName = restoredState.processName
        state.isSystemServer = restoredState.isSystemServer
        state.systemServerClassLoader = restoredState.systemServerClassLoader

        if (restoredState.isSystemServer) {
            val targetClassLoader = restoredState.systemServerClassLoader ?: run {
                DebugLog.w("HookEntry", "hot reloaded system_server without classLoader")
                unhookRemainingOldHandles(oldHandles)
                return
            }
            dispatcher.dispatchSystemServerHookers(targetClassLoader, oldHandles)
        } else {
            restoredState.packages.forEach { packageState ->
                state.recordPackageState(
                    packageName = packageState.packageName,
                    classLoader = packageState.classLoader,
                    appInfo = packageState.appInfo,
                    isFirstPackage = packageState.isFirstPackage,
                    isPackageReady = packageState.isPackageReady,
                    appContext = packageState.appContext,
                    pluginStates = packageState.pluginStates
                )
                state.injectedPackages.add(packageState.packageName)
                if (packageState.appContext != null) {
                    state.pendingAppContextPackages.remove(packageState.packageName)
                }
                dispatcher.dispatchPackageHookers(
                    packageName = packageState.packageName,
                    classLoader = packageState.classLoader,
                    appInfo = packageState.appInfo,
                    isFirstPackage = false,
                    isPackageReady = packageState.isPackageReady,
                    replacementHandles = oldHandles
                )
                if (packageState.isPackageReady) {
                    dispatcher.onRestoredPackageReady(packageState, oldHandles)
                }
            }
        }

        restoreHookerStates(restoredState.hookerStates)

        logHotReloadHandleDiff(oldHandleIds, oldHandles)
        unhookRemainingOldHandles(oldHandles)
        retryHookersAfterHotReload()
        if (state.isSystemServer) settings.scheduleContextRetry()
        // Publication requires the restored host context. The early hot-reload callback runs
        // before package snapshots are restored and cannot be its only recovery boundary.
        settings.publishNativeRuleSwitches()
        if (!state.isSystemServer && processName == "com.android.systemui") {
            val context = restoredState.packages.firstOrNull { it.packageName == processName }?.appContext
            if (context != null) {
                ProcessLifecycleHooks.scheduleSystemUiRecovery(context, state.rootHookers)
            } else {
                DebugLog.w("HookEntry", "SystemUI hot reload recovery missing application context")
            }
        }
        if (state.ownsRuntimeSettings) settings.resumeReload()
        DebugLog.i("HookEntry", "hot reload replacement ready process=$processName roots=${state.rootHookers.size}")
    }

    /** An older module may have no package snapshot; recover only an already living verified host. */
    private fun recoverAppHostState(): HotReloadTargetState? {
        val owner = AppHostRecovery.owner(processName, state.isSystemServer) ?: return null
        return runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.app.Application
                ?: return@runCatching null
            val info = app.applicationInfo
            if (app.packageName != owner || info.packageName != owner ||
                info.uid != android.os.Process.myUid() || info.sourceDir.isNullOrBlank()
            ) return@runCatching null
            DebugLog.i("HookEntry", "recovering live app host without prior state package=$owner process=$processName")
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
        }.onFailure { DebugLog.w("HookEntry", "state-free app host recovery failed process=$processName", it) }
            .getOrNull()
    }

    /** Recover a hooker whose in-place replacement failed without restarting the host process. */
    private fun retryHookersAfterHotReload() {
        state.rootHookers.toList().forEach { it.retryHookIfNeeded() }
    }

    private fun logHotReloadHandleDiff(
        oldHandleIds: Set<String>,
        oldHandles: HotReloadHandleStore
    ) {
        val newHandleIds = state.rootHookers.flatMap { it.collectManagedHookHandles() }
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
            state.rootHookers.toList(), states, { it.hookerName },
            { hooker, saved -> hooker.restoreHotReloadState(saved) },
            { it.recoverGenerationHosts() },
            { hooker, error -> DebugLog.e("HookEntry", "failed to restore/recover ${hooker.hookerName}", error) }
        )
    }
}
