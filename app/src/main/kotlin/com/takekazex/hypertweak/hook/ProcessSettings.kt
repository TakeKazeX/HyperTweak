package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.preferences.LauncherDesktopPrefs
import android.content.Context
import android.content.SharedPreferences
import com.takekazex.hypertweak.hook.bootstrap.RuntimeSettingsParticipants
import com.takekazex.hypertweak.hook.bootstrap.SystemUiSettingsHost
import com.takekazex.hypertweak.hook.nativebridge.NativeRuleStatePublisher
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsStore
import com.takekazex.hypertweak.hook.runtime.settings.SettingsReloadDispatcher
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicLong

/**
 * A process's settings runtime for one module generation: the daemon's remote preferences
 * binding and its bounded retry, live-settings dispatch on the main thread, and publication of
 * native rule switches (launcher payload, SystemUI native state).
 */
internal class ProcessSettings(
    private val module: XposedModule,
    private val state: HookProcessState,
    private val systemUiSettings: SystemUiSettingsHost,
    private val refreshIme: () -> Boolean
) {
    companion object {
        /**
         * The launcher process. It carries no dex of its own for the module's hooks, but it is in
         * the declared scope so LSPosed injects both the native payload (which owns the Dart
         * rules) and this module's Java (which is the only side able to read the preferences).
         */
        const val LAUNCHER_PACKAGE = "com.miui.home"

        /** Bounded retry window for the transient daemon remote-preferences outage. */
        private const val MAX_PREFS_INIT_RETRIES = 4
        private const val PREFS_RETRY_DELAY_MS = 500L
    }

    private val processName get() = state.processName
    private val isSystemServer get() = state.isSystemServer

    val preferenceRetryGeneration = AtomicLong(0L)
    @Volatile private var nativeRuleStatePublisher: NativeRuleStatePublisher? = null
    @Synchronized private fun nativePublisher(): NativeRuleStatePublisher? {
        if (state.generationRetired || processName != "com.android.systemui") return null
        return nativeRuleStatePublisher ?: NativeRuleStatePublisher().also { nativeRuleStatePublisher = it }
    }
    private val runtimeSettingsReceiver by lazy {
        RuntimeSettingsReceiver(object : (Set<String>, Set<String>) -> Unit {
            override fun invoke(groups: Set<String>, keys: Set<String>) = requestReload(groups, keys)
        }, upstreamPackageChanged = if (isSystemServer || processName == "com.android.systemui") {
            { context ->
                if (!state.generationRetired) {
                    com.takekazex.hypertweak.util.platform.UpstreamBackGesture.refresh(context)
                    com.takekazex.hypertweak.hook.runtime.settings.HookSettingsSnapshots.refresh()
                    DebugLog.i("BackGesture", "upstream package refreshed installed=" +
                        com.takekazex.hypertweak.util.platform.UpstreamBackGesture.installed)
                    requestReload(setOf(RuntimeSettingsGroups.BACK))
                }
            }
        } else null)
    }
    /** Held by icon live settings and across the whole hot-reload retirement. */
    val iconSettingsLock = java.util.concurrent.locks.ReentrantLock()
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
                    hookers = { state.rootHookers.toList() },
                    retired = { state.generationRetired },
                    ime = { refreshIme() }
                )
            )
        }
    }
    fun requestReload(groups: Set<String>, keys: Set<String> = emptySet()) {
        if (groups.isEmpty()) return
        settingsHandler.post {
            if (!state.generationRetired) settingsReload.request(groups.intersect(state.runtimeGroups), keys)
        }
    }
    fun cancelReload() {
        // API-102 preparation can run off-main; serialize retirement with live replacement.
        if (android.os.Looper.myLooper() == settingsHandler.looper) settingsReload.cancel() else {
            val done = java.util.concurrent.CountDownLatch(1)
            settingsHandler.post { try { settingsReload.cancel() } finally { done.countDown() } }
            check(done.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "settings reload retirement timed out" }
        }
    }
    fun resumeReload() { settingsHandler.post { settingsReload.resume() } }

    private val runtimeContextRetry by lazy {
        com.takekazex.hypertweak.hook.base.SystemServerStartupRetry("RuntimeSettings", action = {
            val owner = Class.forName("android.app.ActivityThread")
            val thread = owner.getMethod("currentActivityThread").invoke(null) ?: return@SystemServerStartupRetry false
            val context = owner.getMethod("getSystemContext").invoke(thread) as? Context ?: return@SystemServerStartupRetry false
            attachContext(context)
            true
        })
    }
    fun scheduleContextRetry() = runtimeContextRetry.schedule()
    fun cancelContextRetry() = runtimeContextRetry.cancel()

    fun attachContext(context: Context) {
        if (isSystemServer || processName == "com.android.systemui") {
            com.takekazex.hypertweak.util.platform.UpstreamBackGesture.refresh(context)
        }
        if (!state.generationRetired && state.ownsRuntimeSettings) runtimeSettingsReceiver.attach(context)
    }
    fun closeReceiver() = runtimeSettingsReceiver.close()

    @Volatile private var runtimePreferencesSource: SharedPreferences? = null
    @Volatile private var remoteSettingsReady = false
    val hasPreferencesSource: Boolean get() = runtimePreferencesSource != null
    // Legacy keys outside the committed registry still arrive through the daemon listener.
    // Coalesce remove/put notifications so a callback never polls an incomplete backend rewrite.
    private val callbackSnapshotRefresh = Runnable {
        if (!state.generationRetired) {
            com.takekazex.hypertweak.hook.runtime.settings.HookSettingsSnapshots.refresh()
            DebugLog.controlsChanged()
        }
    }
    private val runtimePreferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!state.generationRetired) {
            Preferences.invalidateRuntimeReadCache()
            settingsHandler.removeCallbacks(callbackSnapshotRefresh)
            settingsHandler.postDelayed(callbackSnapshotRefresh, 150L)
            // The daemon rewrites complete subsets (remove + put), and may report null or
            // unchanged keys. Only the acknowledged snapshot diff owns feature recreation.
            if (key == null || key == RuntimeSettingsGroups.REVISION || RuntimeSettingsGroups.owns(key))
                runtimeSettingsReceiver.resync()
        }
    }

    fun detachPreferencesListener() {
        settingsHandler.removeCallbacks(callbackSnapshotRefresh)
        runtimePreferencesSource?.let { source ->
            runCatching { source.unregisterOnSharedPreferenceChangeListener(runtimePreferencesListener) }
        }
        runtimePreferencesSource = null
    }

    private fun bindPreferencesListener(source: SharedPreferences) {
        if (runtimePreferencesSource === source) return
        detachPreferencesListener()
        runCatching {
            source.registerOnSharedPreferenceChangeListener(runtimePreferencesListener)
            runtimePreferencesSource = source
        }.onFailure { DebugLog.w("NativeRules", "runtime settings listener unavailable", it) }
    }

    /** First step of retirement: stop remote-settings use and invalidate pending prefs retries. */
    fun markRetired() {
        remoteSettingsReady = false
        preferenceRetryGeneration.incrementAndGet()
    }

    fun saveNativeSnapshot(): Any? = nativeRuleStatePublisher?.saveSnapshot()
    fun restoreNativeSnapshot(snapshot: Any?) { nativePublisher()?.restoreSnapshot(snapshot) }
    fun attachNativePublisher(context: Context) { nativePublisher()?.attach(context) }
    fun closeNativePublisher() {
        try { nativeRuleStatePublisher?.close() } finally { nativeRuleStatePublisher = null }
    }

    fun initPreferences() {
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
        state.rootHookers.toList().forEach { it.retryHookIfNeeded() }
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
    fun publishNativeRuleSwitches() {
        // An unavailable daemon must not overwrite the last good native snapshot
        // with default values during boot. The successful bind/retry publishes it.
        if (state.generationRetired) return
        if (processName == "com.android.systemui") {
            val context = state.packageStates[processName]?.appContext
                ?: com.takekazex.hypertweak.hook.base.HostContext.appContextOrNull
            val publisher = nativePublisher() ?: return
            if (context != null) { publisher.attach(context); runtimeSettingsReceiver.attach(context) }
            publisher.publish()
            return
        }
        if ((!remoteSettingsReady && !RuntimeSettingsStore.owns(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION)) || processName != LAUNCHER_PACKAGE) return
        runCatching {
            NativeRules.applyRuleSwitches(
                hideRecentsClearButton = Preferences.getBoolean(PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON, false),
                openedFolderColumns = LauncherDesktopPrefs.openedFolderColumns(),
                contextualSearchLongPress = Preferences.getBoolean(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS, false),
                assistantWidgets = Preferences.getBoolean(PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT, false)
            )
        }.onFailure { t ->
            DebugLog.w("HookEntry", "native rule switch publish failed", t)
        }
    }

    /** Runs one attempt at binding the daemon's remote preferences; true on success. */
    fun tryInitPreferences(): Boolean {
        return try {
            val remotePrefs = module.getRemotePreferences(AppSettingsKeys.NAME)
            Preferences.init(remotePrefs)
            RuntimeSettingsGroups.registerImeTargets(Preferences.getStringSet(PlatformSettingsKeys.KEY_AOSP_IME_PACKAGES))
            bindPreferencesListener(remotePrefs)
            requestReload(state.runtimeGroups)
            DebugLog.d("HookEntry", "processName=$processName loaded remotePrefs groups=${state.runtimeGroups}")
            remoteSettingsReady = true
            true
        } catch (t: Throwable) {
            remoteSettingsReady = false
            Preferences.noteRemoteBackendUnavailable()
            DebugLog.e("HookEntry", "failed to init Preferences (remote channel unavailable; using cache fallback)", t)
            false
        }
    }
}
