package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys

import com.takekazex.hypertweak.hook.logging.AppLogStore
import com.takekazex.hypertweak.hook.nativebridge.NativeRuleStateSnapshot
import com.takekazex.hypertweak.hook.preferences.RuntimeSettingsPreferences
import com.takekazex.hypertweak.hook.preferences.SettingsBackupManager
import com.takekazex.hypertweak.hook.preferences.SettingsMutationWriter
import com.takekazex.hypertweak.hook.runtime.settings.BackGestureSettingsSnapshot
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsReadCache
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsStore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.BackGestureKeys
import com.takekazex.hypertweak.settings.contract.IconConfig
import com.takekazex.hypertweak.settings.contract.IconConfigDecoder
import com.takekazex.hypertweak.settings.contract.IconConfigMigration
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsSnapshot
import com.takekazex.hypertweak.util.logging.SettingsDiagnostics
import com.takekazex.hypertweak.hook.base.HostContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Preferences {
    private val backups = SettingsBackupManager(object : SettingsBackupManager.Storage {
        override val logLock get() = Preferences.logLock
        override val settingsMutationLock get() = Preferences.settingsMutationLock
        override val isInitialized get() = Preferences.isInitialized
        override val canReadRemote get() = Preferences.isInitialized && !isLocalOnly && !remoteBackendUnavailable
        override val remotePrefs get() = Preferences.remotePrefs
        override val localSourcePrefs get() = Preferences.localSourcePrefs
        override fun cache() = getLocalCache()
        override fun invalidate() = memoClear()
        override fun flush() = mutations.awaitPendingWrites()
        override fun isRuntimeKey(key: String) = Preferences.isRuntimeKey(key)
        override fun backupMetadata() = org.json.JSONObject()
            .put("module", org.json.JSONObject()
                .put("version_name", com.takekazex.hypertweak.BuildConfig.VERSION_NAME)
                .put("version_code", com.takekazex.hypertweak.BuildConfig.VERSION_CODE))
            .put("system", org.json.JSONObject()
                .put("device", android.os.Build.DEVICE)
                .put("android_release", android.os.Build.VERSION.RELEASE)
                .put("api", android.os.Build.VERSION.SDK_INT)
                .put("build_fingerprint", android.os.Build.FINGERPRINT))
        override fun commit(snapshot: NativeRuleStateSnapshot, replacement: Map<String, *>, edit: SharedPreferences.Editor.() -> Unit) =
            commitRemoteMutation(snapshot, replacement, edit)
    })

    fun backGestureBoolean(key: String, defaultValue: Boolean): Boolean =
        if (RuntimeSettingsStore.owns(key)) getBoolean(key, defaultValue) else runCatching {
            if (isInitialized) remotePrefs.getBoolean(key, defaultValue) else defaultValue
        }.getOrDefault(defaultValue)

    /** The vendored runtime uses the same authoritative source and listeners as other hooks. */
    fun backGesturePreferences(): SharedPreferences = RuntimeSettingsPreferences.bind(compatibilityReader)
    private val compatibilityReader = object : RuntimeSettingsPreferences.Reader {
        override fun source() = runtimePreferenceSource()
        override fun contains(key: String) = Preferences.contains(key)
        override fun getBoolean(key: String, default: Boolean) = Preferences.getBoolean(key, default)
        override fun getInt(key: String, default: Int) = Preferences.getInt(key, default)
        override fun getFloat(key: String, default: Float) = Preferences.getFloat(key, default)
        override fun getString(key: String, default: String) = Preferences.getString(key, default)
        override fun getStringSet(key: String) = Preferences.getStringSet(key)
    }
    internal fun runtimePreferenceSource(): SharedPreferences? = if (isInitialized) remotePrefs else null
    private const val CACHE_NAME = "hypertweak_cache"

    @Volatile private var cachedIconSnapshot: RuntimeSettingsSnapshot? = null
    @Volatile private var cachedIconConfig: IconConfig? = null

    /** Decode once per accepted icon settings snapshot. Before the runtime channel is ready,
     * use the regular typed storage boundary so remote-cache and epoch rules remain intact. */
    internal fun iconConfig(): IconConfig {
        val snapshot = RuntimeSettingsStore.latest()
        if (snapshot != null && RuntimeSettingsStore.owns(IconSettingsKeys.KEY_ICON_SIGNAL_STYLE)) {
            synchronized(this) {
                if (cachedIconSnapshot === snapshot) cachedIconConfig?.let { return it }
                return IconConfigDecoder.decode { snapshot.values[it] }.also {
                    cachedIconSnapshot = snapshot
                    cachedIconConfig = it
                }
            }
        }
        val fallbackCacheKey = "__icon_config_snapshot"
        (memoGet(fallbackCacheKey) as? IconConfig)?.let { return it }
        return IconConfigDecoder.decode { key ->
            if (!contains(key)) null else when (IconConfigDecoder.defaultValue(key) ?: IconConfigMigration.legacyType(key)) {
                is Boolean -> getBoolean(key)
                is Int -> getInt(key)
                is Float -> getFloat(key)
                is String -> getString(key)
                is Set<*> -> getStringSet(key)
                else -> null
            }
        }.also { memoPut(fallbackCacheKey, it) }
    }

    private lateinit var remotePrefs: SharedPreferences
    // Only the module Application may supply this store via useLocalOnly; never a host cache.
    @Volatile private var localSourcePrefs: SharedPreferences? = null
    @Volatile
    private var localCachePrefs: SharedPreferences? = null
    @Volatile
    private var cachedAppContext: Context? = null
    private val appCache = com.takekazex.hypertweak.hook.base.DeferredAppCache<Pair<Context, SharedPreferences>>(
        clock = android.os.SystemClock::elapsedRealtime
    )

    /**
     * True once the LSPosed daemon's remote-preferences channel is known to be unavailable
     * (e.g. `getRemotePreferences` throws "Framework returns null"). When this is set the getters
     * fall back to the per-process cache instead of silently returning defaults, so a daemon
     * hiccup cannot make every preference-gated hook read its "off" default.
     */
    @Volatile
    private var remoteBackendUnavailable = false

    private var isLocalOnly = false
    private val serializedWriter = java.util.concurrent.ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(),
        { r -> Thread(r, "HyperTweak-Prefs").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }
    /** Serializes bulk mutations with the ordinary preference writes queued below. */
    private val settingsMutationLock = Any()

    /**
     * Bumped on every full reset ([clearAllSettings]). Hooked processes keep a local cache
     * (`hypertweak_cache.xml` in each app's data dir) that the module cannot reach; the
     * epoch stored in the authoritative remote prefs invalidates those caches so a reset
     * actually takes effect instead of serving stale values.
     */
    private const val KEY_PREFS_EPOCH = "prefs_epoch"
    private const val INITIAL_EPOCH = 0L

    /** Module-page saveable state identity; unavailable storage must not invalidate pending edits. */
    fun settingsEpochForUi(): Long? {
        if (isInitialized && !remoteBackendUnavailable) runCatching { remotePrefs.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }
            .getOrNull()?.let { return it }
        runCatching { localSourcePrefs?.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }
            .getOrNull()?.let { return it }
        return runCatching { getLocalCache()?.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }.getOrNull()
    }

    private const val KEY_PENDING_RESTART_BOOT_TOKEN = "pending_restart_boot_token"
    private const val KEY_DIRTY_TWEAK_KEYS = "dirty_tweak_keys"
    private const val KEY_TWEAK_BASELINE_PREFIX = "tweak_baseline_"
    private const val KEY_MANUAL_PENDING_RESTART_SCOPES = "manual_pending_restart_scopes"
    private const val KEY_FIRST_RUN_TOKEN = "first_run_token"
    private const val KEY_FINGERPRINT_SPLIT_BASELINE_MIGRATED = "fingerprint_split_baseline_migrated"

    /** Keys used by the module runtime itself rather than by user-facing configuration. */
    private fun isRuntimeKey(key: String): Boolean =
        key == RuntimeSettingsGroups.REVISION ||
            key == ScreenshotSettingsSnapshot.REVISION ||
            // Device-side properties are not portable configuration. Reset/restore does not
            // authorize root commands; the page always re-reads the actual device state.
            key == PlatformSettingsKeys.KEY_WIFI_CNSS_DIAG_ENABLED ||
            key == PlatformSettingsKeys.KEY_WIFI_TCPDUMP_ENABLED ||
            key == KEY_PREFS_EPOCH ||
            key == PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION ||
            key == BackGestureSettingsSnapshot.REVISION ||
            key == KEY_PENDING_RESTART_BOOT_TOKEN ||
            key == KEY_DIRTY_TWEAK_KEYS ||
            key.startsWith(KEY_TWEAK_BASELINE_PREFIX) ||
            key == KEY_MANUAL_PENDING_RESTART_SCOPES ||
            key == PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES ||
            key == KEY_FIRST_RUN_TOKEN ||
            // Obsolete per-device package snapshot from the former batch implementation.
            key == "notification_whitelist_bypass" ||
            key == KEY_FINGERPRINT_SPLIT_BASELINE_MIGRATED ||
            AppLogStore.isRuntimeKey(key)

    /**
     * Wipes every setting in every storage location the module uses:
     * - the module's own `hypertweak_settings` / `hypertweak_cache` files (deleted on
     *   uninstall anyway);
     * - the LSPosed daemon's remote copy (`modules_config.db`), which survives uninstall
     *   and is why settings come back after a reinstall.
     * The epoch bump makes hooked processes fall back to defaults instead of their local
     * caches. After this call every setting is at its default; hook processes pick the new
     * state up on their next read without a reboot.
     */
    fun clearAllSettings(): Boolean = backups.clearAllSettings()
    fun exportSettings(): String = backups.exportSettings()
    fun restoreSettings(json: String): Int = backups.restoreSettings(json)

    /** True when the remote copy still holds settings (i.e. a previous install left config behind). */
    fun hasRemoteConfig(): Boolean {
        if (!isInitialized) return false
        return runCatching { remotePrefs.all.keys.any { it != KEY_PREFS_EPOCH } }.getOrDefault(false)
    }

    /**
     * Writes the value under [block] into the local cache together with the current remote epoch.
     * The cache only stores values that were read authoritatively from the daemon, so the epoch is
     * stamped at the same moment. This is what lets hook processes (e.g. SystemUI) use their local
     * cache as a fallback for keys the daemon does not carry, while still treating the cache as
     * stale after a [clearAllSettings] bumps the remote epoch.
     */
    private fun cacheWrite(cache: SharedPreferences?, block: SharedPreferences.Editor.() -> Unit) {
        if (cache == null) return
        // Async apply, matching the historical getter writes: the cache is only a fallback, so a
        // hot-path read (per frame / per touch) must not pay synchronous disk I/O. The epoch is
        // stamped in the same transaction so the cache always carries it alongside the value.
        runCatching {
            cache.edit {
                block()
                putLong(KEY_PREFS_EPOCH, remotePrefs.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH))
            }
        }.onFailure { DebugLog.w("Preferences", "cache write failed", it) }
    }

    /**
     * False when the remote prefs were wiped after this process last cached values, so the
     * cache must not be trusted and reads fall back to defaults.
     */
    private fun cacheEpochMatchesRemote(): Boolean {
        val cache = getLocalCache() ?: return true
        val remoteEpoch = runCatching { remotePrefs.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }.getOrDefault(INITIAL_EPOCH)
        if (!cache.contains(KEY_PREFS_EPOCH)) {
            // A cache without the epoch key was populated by authoritative daemon reads from a
            // build that predates the epoch stamping (one-time transition). The values are fresh,
            // so adopt the current remote epoch and treat the cache as matching. From here on the
            // cache carries the epoch, so a later clearAllSettings bump makes it mismatch and the
            // cache is correctly treated as stale instead of serving pre-reset values forever.
            cacheWrite(cache) { /* only stamp the epoch */ }
            return true
        }
        val cacheEpoch = runCatching { cache.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }.getOrDefault(INITIAL_EPOCH)
        return remoteEpoch == cacheEpoch
    }

    /** The module Application's local store; injected processes have none. */
    private fun appLocalStore(): SharedPreferences? = localSourcePrefs
    internal val appLogs = AppLogStore(::appLocalStore) { getBoolean(PlatformSettingsKeys.KEY_RECORD_LOGS, true) }
    private val logLock get() = appLogs.lock

    /**
     * Short-lived process-local read memo for the getters below. Without it every getter call
     * pays at least one binder transaction to the LSPosed daemon even when the value has not
     * changed, which adds up on per-event and per-frame preference reads (touch dispatch, wallpaper
     * events, log gates, broadcast hooks). Entries expire after 100 ms, so a setting
     * written by the module is picked up on the next read shortly afterwards without turning every
     * hot-path read into a binder round-trip. Puts, resets and full wipes invalidate eagerly.
     */
    private val memo = RuntimeSettingsReadCache.memo
    private fun memoGet(key: String): Any? = memo[key]
    private fun memoPut(key: String, value: Any?) { memo[key] = value }
    private fun memoInvalidate(key: String) = memo.invalidate(key)
    private fun memoClear() = memo.clear()

    internal fun nativeRuleSnapshot(): NativeRuleStateSnapshot? {
        if (RuntimeSettingsStore.owns(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION)) return RuntimeSettingsStore.values()?.let { values -> NativeRuleStateSnapshot.read { values } }
        if (!this::remotePrefs.isInitialized || isLocalOnly) return null
        return NativeRuleStateSnapshot.read { remotePrefs.all }
    }

    /** A remote change callback is authoritative; do not reuse pre-notification reads. */
    fun invalidateRuntimeReadCache() = memoClear()

    internal fun inspectDebugSettings() = mutations.inspect()
    internal fun patchDebugSettings(changes: Map<String, Any>, remove: Set<String>, expectedRevision: Long?,
        authorize: () -> Unit) = mutations.patch(changes, remove, expectedRevision, authorize)
    internal fun isDebugUserSetting(key: String) = !isRuntimeKey(key)

    fun init(prefs: SharedPreferences, useLocalOnly: Boolean = false) {
        memoClear()
        remoteBackendUnavailable = false
        if (useLocalOnly) {
            localSourcePrefs = prefs
            // Only apply local prefs as fallback if remote prefs haven't been set yet
            if (!this::remotePrefs.isInitialized || isLocalOnly) {
                remotePrefs = prefs
                isLocalOnly = true
            }
        } else {
            // Remote prefs always win and can upgrade a local-only instance
            remotePrefs = prefs
            isLocalOnly = false
        }
        // Also run when local storage arrives after an early service binding. No marker is
        // needed: an empty key set is a no-op, and rejected cleanups retry on the next bind.
        cleanRemoteAppLogs()
        com.takekazex.hypertweak.hook.runtime.settings.HookSettingsSnapshots.refresh()
    }

    private fun cleanRemoteAppLogs() {
        val local = appLocalStore() ?: return
        if (!isInitialized || isLocalOnly || local === remotePrefs) return
        val remote = remotePrefs
        serializedWriter.execute {
            runCatching { AppLogStore.purgeRemoteRecords(remote) }
                .onFailure { SettingsDiagnostics.w("Preferences", "remote app-log cleanup failed; retrying on next bind", it) }
        }
    }

    fun useLocalBackend() {
        memoClear()
        localSourcePrefs?.let { remotePrefs = it; isLocalOnly = true }
    }

    private val mutations = SettingsMutationWriter(object : SettingsMutationWriter.Storage {
        override val settingsMutationLock get() = Preferences.settingsMutationLock
        override val isInitialized get() = Preferences.isInitialized
        override val remotePrefs get() = Preferences.remotePrefs
        override val localSourcePrefs get() = Preferences.localSourcePrefs
        override val isLocalOnly get() = Preferences.isLocalOnly
        override val serializedWriter get() = Preferences.serializedWriter
        override fun invalidate(key: String) = memoInvalidate(key)
    })

    private fun write(localKey: String? = null, nativeMutation: Pair<String, Any>? = null,
        runtimeMutation: Pair<String, Any>? = null, runtimeChanges: Map<String, Any> = emptyMap(),
        block: SharedPreferences.Editor.() -> Unit) =
        mutations.write(localKey, nativeMutation, runtimeMutation, runtimeChanges, block)

    private fun commitRemoteMutation(nativeSnapshot: NativeRuleStateSnapshot,
        replacement: Map<String, *>, block: SharedPreferences.Editor.() -> Unit): Boolean =
        mutations.commitRemoteMutation(nativeSnapshot, replacement, block)

    /**
     * Blocks until every queued remote write has been applied. The remote copy lives in the
     * LSPosed daemon and is written asynchronously through [serializedWriter], so a hooked
     * process restarted right after a setting change (e.g. the Restart SystemUI action on a
     * tuning page) can start before the daemon has the new values and read stale ones. Call
     * this before restarting hooked processes that must observe the latest settings.
     */
    fun flush() {
        if (!isInitialized || isLocalOnly) return
        val latch = CountDownLatch(1)
        serializedWriter.execute { latch.countDown() }
        runCatching { latch.await(3, TimeUnit.SECONDS) }
    }

    fun initLocalCache(context: Context): Boolean {
        val result = appCache.open {
            // A package-ready Context can arrive before credential storage is unlocked.
            // This is a normal deferral; retain the remote backend without touching CE files.
            if (!context.isDeviceProtectedStorage &&
                context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == false) {
                Result.success(null)
            } else com.takekazex.hypertweak.hook.base.OptionalAppCache.open(
                directory = { context.dataDir },
                create = { context to context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE) }
            )
        }
        val cache = result.onFailure {
            SettingsDiagnostics.w("Preferences", "host storage cache unavailable; using remote preferences", it)
        }.getOrNull() ?: return false
        // Publish a complete, usable pair only. A provider's system context must not displace a
        // valid application cache, or prevent package-ready consumers from being registered.
        cachedAppContext = cache.first
        localCachePrefs = cache.second
        return true
    }

    /** Logging must never initialize storage, mirror settings, or log a backend failure. */
    internal fun logLevel(default: Int): Int {
        (memoGet(AppSettingsKeys.KEY_LOG_LEVEL) as? Int)?.let { return it }
        if (isInitialized && !remoteBackendUnavailable) runCatching { remotePrefs.getInt(AppSettingsKeys.KEY_LOG_LEVEL, default) }
            .getOrNull()?.let { memoPut(AppSettingsKeys.KEY_LOG_LEVEL, it); return it }
        return runCatching { (localSourcePrefs ?: localCachePrefs)?.getInt(AppSettingsKeys.KEY_LOG_LEVEL, default) }
            .getOrNull() ?: default
    }

    /**
     * Lazily resolves an app context so the cache can be loaded before [initLocalCache] runs
     * (e.g. during hook attach, on the same event a gated hooker reads its preference). Best
     * effort: returns null when no application context is available yet (e.g. system_server).
     */
    private fun resolveAppContext(): Context? {
        cachedAppContext?.let { return it }
        val ctx = HostContext.appContextOrNull
        return ctx
    }

    private fun getLocalCache(): SharedPreferences? {
        localCachePrefs?.let { return it }
        val ctx = runCatching { resolveAppContext() }.getOrNull() ?: return null
        return if (initLocalCache(ctx)) localCachePrefs else null
    }

    /** True when the remote prefs channel is unavailable and [getLocalCache] is the only source. */
    fun isRemoteBackendUnavailable(): Boolean = remoteBackendUnavailable

    /** Marks the remote daemon channel unavailable (called when [init] fails with remote prefs). */
    fun noteRemoteBackendUnavailable() {
        if (remoteBackendUnavailable) return
        remoteBackendUnavailable = true
        memoClear()
        SettingsDiagnostics.d("Preferences", "remote preferences backend unavailable; falling back to local cache")
    }

    val isInitialized: Boolean
        get() = this::remotePrefs.isInitialized

    /**
     * Returns whether a key is explicitly present in the authoritative store (or its valid local
     * fallback). This is needed for aliased settings where an explicit `false`/`0` must beat an
     * older key, rather than being indistinguishable from the default value.
     */
    fun contains(key: String): Boolean {
        if (AppLogStore.isRuntimeKey(key)) return appLocalStore()?.contains(key) == true
        if (RuntimeSettingsStore.owns(key)) return RuntimeSettingsStore.value(key) != null
        if (!isInitialized || remoteBackendUnavailable) return (appLocalStore() ?: getLocalCache())?.contains(key) == true
        return runCatching {
            if (remotePrefs.contains(key)) {
                true
            } else if (cacheEpochMatchesRemote()) {
                getLocalCache()?.contains(key) == true
            } else {
                false
            }
        }.getOrElse {
            localSourcePrefs?.contains(key) == true || getLocalCache()?.contains(key) == true
        }
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        if (AppLogStore.isRuntimeKey(key)) return appLocalStore()?.getBoolean(key, default) ?: default
        if (RuntimeSettingsStore.owns(key)) return (RuntimeSettingsStore.value(key) as? Boolean) ?: default
        if (!isInitialized || remoteBackendUnavailable) return (appLocalStore() ?: getLocalCache())?.getBoolean(key, default) ?: default
        // Safe cast: the memo map is keyed by key alone, so a key read through two different typed
        // getters would otherwise throw ClassCastException at a hook boundary with no fallback.
        (memoGet(key) as? Boolean)?.let { return it }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getBoolean(key, default)
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getBoolean(key, !v) != v)) {
                    cacheWrite(cache) { putBoolean(key, v) }
                }
                v
            } else if (cacheEpochMatchesRemote()) {
                getLocalCache()?.getBoolean(key, default) ?: default
            } else {
                default
            }
        } catch (_: Throwable) {
            localSourcePrefs?.getBoolean(key, default) ?: getLocalCache()?.getBoolean(key, default) ?: default
        }
        memoPut(key, value)
        return value
    }

    fun getInt(key: String, default: Int = 0): Int {
        if (AppLogStore.isRuntimeKey(key)) return appLocalStore()?.getInt(key, default) ?: default
        if (RuntimeSettingsStore.owns(key)) return (RuntimeSettingsStore.value(key) as? Int) ?: default
        if (!isInitialized || remoteBackendUnavailable) return (appLocalStore() ?: getLocalCache())?.getInt(key, default) ?: default
        (memoGet(key) as? Int)?.let { return it }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getInt(key, default)
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getInt(key, v - 1) != v)) {
                    cacheWrite(cache) { putInt(key, v) }
                }
                v
            } else if (cacheEpochMatchesRemote()) {
                getLocalCache()?.getInt(key, default) ?: default
            } else {
                default
            }
        } catch (_: Throwable) {
            localSourcePrefs?.getInt(key, default) ?: getLocalCache()?.getInt(key, default) ?: default
        }
        memoPut(key, value)
        return value
    }

    fun getFloat(key: String, default: Float = 1f): Float {
        if (AppLogStore.isRuntimeKey(key)) return appLocalStore()?.getFloat(key, default) ?: default
        if (RuntimeSettingsStore.owns(key)) return (RuntimeSettingsStore.value(key) as? Float) ?: default
        if (!isInitialized || remoteBackendUnavailable) return (appLocalStore() ?: getLocalCache())?.getFloat(key, default) ?: default
        (memoGet(key) as? Float)?.let { return it }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getFloat(key, default)
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getFloat(key, v - 1f) != v)) {
                    cacheWrite(cache) { putFloat(key, v) }
                }
                v
            } else if (cacheEpochMatchesRemote()) {
                getLocalCache()?.getFloat(key, default) ?: default
            } else {
                default
            }
        } catch (_: Throwable) {
            localSourcePrefs?.getFloat(key, default) ?: getLocalCache()?.getFloat(key, default) ?: default
        }
        memoPut(key, value)
        return value
    }

    fun putBoolean(key: String, value: Boolean) {
        memoInvalidate(key)
        write(localKey = key, nativeMutation = if (key == PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON || key == PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS || key == PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT || key == PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC || key == PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW || key == PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK || key == PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS || key == PlatformSettingsKeys.KEY_NATIVE_PAD_DOCK) key to value else null,
            runtimeMutation = if (RuntimeSettingsSnapshot.owns(key)) key to value else null) { putBoolean(key, value) }
    }

    fun putInt(key: String, value: Int) {
        memoInvalidate(key)
        write(localKey = key, nativeMutation = if (key == LauncherDesktopKeys.KEY_ICON_THEME_MODE || key == LauncherDesktopKeys.KEY_ICON_THEME_COLOR || key == PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS || key == LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT || key == LauncherDesktopKeys.KEY_GRID || key == PlatformSettingsKeys.KEY_NATIVE_DOCK_GLASS) key to value else null,
            runtimeMutation = if (RuntimeSettingsSnapshot.owns(key)) key to value else null) { putInt(key, value) }
    }

    fun putFloat(key: String, value: Float) {
        memoInvalidate(key)
        write(localKey = key, runtimeMutation = if (RuntimeSettingsSnapshot.owns(key)) key to value else null) { putFloat(key, value) }
    }

    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String> {
        if (AppLogStore.isRuntimeKey(key)) return appLocalStore()?.getStringSet(key, default)?.toSet() ?: default.toSet()
        if (RuntimeSettingsStore.owns(key)) {
            @Suppress("UNCHECKED_CAST")
            return (RuntimeSettingsStore.value(key) as? Set<String>) ?: default
        }
        if (!isInitialized || remoteBackendUnavailable) return (appLocalStore() ?: getLocalCache())?.getStringSet(key, default) ?: default
        memoGet(key)?.let { return ((it as? Set<*>) ?: default).map { element -> element as String }.toSet() }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getStringSet(key, default) ?: default
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getStringSet(key, emptySet()) != v)) {
                    cacheWrite(cache) { putStringSet(key, v) }
                }
                v
            } else if (cacheEpochMatchesRemote()) {
                getLocalCache()?.getStringSet(key, default) ?: default
            } else {
                default
            }
        } catch (_: Throwable) {
            localSourcePrefs?.getStringSet(key, default) ?: getLocalCache()?.getStringSet(key, default) ?: default
        }
        // Defensive copy: SharedPreferences returns a fresh set per call, and callers may hold on
        // to the result, so the memoized instance must never be handed out directly.
        val copy = value.toSet()
        memoPut(key, copy)
        return copy
    }

    fun putStringSet(key: String, value: Set<String>) {
        memoInvalidate(key)
        write(localKey = key, runtimeMutation = if (RuntimeSettingsSnapshot.owns(key)) key to value else null) { putStringSet(key, value) }
    }

    fun getString(key: String, default: String = ""): String {
        if (AppLogStore.isRuntimeKey(key)) return appLocalStore()?.getString(key, default) ?: default
        if (RuntimeSettingsStore.owns(key)) return (RuntimeSettingsStore.value(key) as? String) ?: default
        if (!isInitialized || remoteBackendUnavailable) return (appLocalStore() ?: getLocalCache())?.getString(key, default) ?: default
        (memoGet(key) as? String)?.let { return it }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getString(key, default) ?: default
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getString(key, "") != v)) {
                    cacheWrite(cache) { putString(key, v) }
                }
                v
            } else if (cacheEpochMatchesRemote()) {
                getLocalCache()?.getString(key, default) ?: default
            } else {
                default
            }
        } catch (_: Throwable) {
            localSourcePrefs?.getString(key, default) ?: getLocalCache()?.getString(key, default) ?: default
        }
        memoPut(key, value)
        return value
    }

    fun putString(key: String, value: String) {
        memoInvalidate(key)
        write(localKey = key, runtimeMutation = if (RuntimeSettingsSnapshot.owns(key)) key to value else null) { putString(key, value) }
    }

    /** Writes [changes] as one acknowledged runtime snapshot; feature readers own the keys. */
    internal fun commitRuntimeChanges(changes: Map<String, Any>, edit: SharedPreferences.Editor.() -> Unit) {
        changes.keys.forEach(::memoInvalidate)
        write(runtimeChanges = changes, block = edit)
    }

    /** One acknowledged patch of [changes] and [remove]; throws when the backend rejects it. */
    internal fun patchSettings(changes: Map<String, Any>, remove: Set<String>) {
        mutations.patch(changes, remove, null) {}
        (changes.keys + remove).forEach(::memoInvalidate)
    }

    /** The module app's local settings file, used only to recover writes made before publishing. */
    internal fun localSource(): SharedPreferences? = localSourcePrefs

    fun putStringSynchronous(key: String, value: String) = mutations.putStringSynchronous(key, value)

    @Volatile private var reportedHookLogWrite = false
    fun appendDebugLog(processTag: String, line: String) = appendDebugLogs(processTag, listOf(line))
    fun appendDebugLogs(processTag: String, lines: List<String>) {
        // A remote backend is also used by the module app. App-local initialization, rather
        // than remote/local backend selection, is the authority to persist app diagnostics.
        if (appLocalStore() == null) {
            if (!reportedHookLogWrite) {
                reportedHookLogWrite = true
                SettingsDiagnostics.d("Preferences", "ignoring injected-process app-log write; use framework logger")
            }
            return
        }
        appLogs.appendDebugLogs(processTag, lines)
    }
    fun getDebugLog(): String = appLogs.getDebugLog()
    fun clearDebugLog() = appLogs.clearDebugLog()
    fun rotateLogSessionIfNeeded(token: String) = appLogs.rotateLogSessionIfNeeded(token)

}
