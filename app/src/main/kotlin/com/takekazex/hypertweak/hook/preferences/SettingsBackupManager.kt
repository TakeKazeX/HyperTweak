package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.hook.nativebridge.NativeRuleStateSnapshot
import com.takekazex.hypertweak.hook.runtime.settings.BackGestureSettingsSnapshot
import android.content.SharedPreferences
import androidx.core.content.edit
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import org.json.JSONObject

internal class SettingsBackupManager(private val storage: Storage) {
    interface Storage {
        val logLock: Any
        val settingsMutationLock: Any
        val isInitialized: Boolean
        val canReadRemote: Boolean get() = isInitialized
        val remotePrefs: SharedPreferences
        val localSourcePrefs: SharedPreferences?
        fun cache(): SharedPreferences?
        fun invalidate()
        fun flush()
        fun isRuntimeKey(key: String): Boolean
        fun backupMetadata(): JSONObject = JSONObject()
        fun commit(snapshot: NativeRuleStateSnapshot, replacement: Map<String, *>, edit: SharedPreferences.Editor.() -> Unit): Boolean
    }
    fun clearAllSettings(): Boolean {
        synchronized(storage.logLock) {
            synchronized(storage.settingsMutationLock) {
                if (!storage.isInitialized) return false
                storage.invalidate()
                val epoch = runCatching {
                    storage.remotePrefs.getLong("prefs_epoch", 0L)
                }.getOrDefault(0L) + 1
                val nativeRevision = android.os.SystemClock.elapsedRealtimeNanos().coerceAtLeast(1)
                val nativeReset = NativeRuleStateSnapshot(false, 3, false, nativeRevision, epoch)

                // Queue the clear behind every ordinary async write and wait for it. Without this
                // ordering, a setting changed immediately before the clear could be committed
                // after the clear and silently resurrect the old configuration.
                requireNotNull(BackGestureSettingsSnapshot.read(mapOf(
                    BackGestureSettingsSnapshot.REVISION to nativeRevision, "prefs_epoch" to epoch)))
                val committed = storage.commit(nativeReset, emptyMap<String, Any>()) {
                    clear()
                    putLong("prefs_epoch", epoch)
                    putLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, nativeRevision)
                }
                if (!committed) return false
                runCatching { storage.localSourcePrefs?.edit(commit = true) { clear().putLong("prefs_epoch", epoch).putLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, nativeRevision) } }
                val cache = storage.cache()
                runCatching { cache?.edit(commit = true) { clear().putLong("prefs_epoch", epoch) } }
                return true
            }
        }
    }

    /** Serializes the user-facing configuration into a portable, versioned JSON document. */
    fun exportSettings(): String {
        synchronized(storage.settingsMutationLock) {
            // Ordinary puts update the app-local source immediately but reach the daemon through
            // the serialized writer. Drain that queue so a backup taken right after a toggle also
            // contains the latest remote value.
            storage.flush()
            val remote = if (storage.canReadRemote) runCatching { storage.remotePrefs.all }.getOrNull() else null
            val source = remote ?: checkNotNull(storage.localSourcePrefs) {
                "No readable settings source for backup"
            }.all
            return SettingsBackupCodec.encode(
                source.filterKeys { !storage.isRuntimeKey(it) }, metadata = storage.backupMetadata()
                    .put("snapshot_source", if (remote != null) "backend" else "local"))
        }
    }

    /**
     * Replaces the current user-facing configuration from a backup document.
     *
     * Runtime bookkeeping (restart baselines, debug logs, and preference epochs) is intentionally
     * kept out of the backup. A new epoch invalidates old hooked-process caches so restored values
     * are read from the daemon instead of being shadowed by stale per-process copies.
     *
     * @return number of restored settings.
     */
    fun restoreSettings(json: String): Int {
        // Ignore runtime bookkeeping even if a hand-edited backup contains those reserved keys.
        val restored = SettingsBackupCodec.decode(json).filterKeys { !storage.isRuntimeKey(it) }
        synchronized(storage.settingsMutationLock) {
            check(storage.isInitialized) { "Preferences are not initialized" }
            storage.flush()
            val previousEpoch = storage.remotePrefs.getLong("prefs_epoch", 0L)
            check(previousEpoch in 0 until Long.MAX_VALUE) { "Invalid settings epoch" }
            val epoch = previousEpoch + 1
            val nativeRevision = android.os.SystemClock.elapsedRealtimeNanos().coerceAtLeast(1)
            val nativeReset = requireNotNull(NativeRuleStateSnapshot.read {
                restored + mapOf(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION to nativeRevision, "prefs_epoch" to epoch)
            }) { "Invalid native settings in backup" }
            val currentKeys = buildSet {
                storage.remotePrefs.all.keys
                    .filterNot(storage::isRuntimeKey)
                    .forEach(::add)
                storage.localSourcePrefs?.all.orEmpty().keys
                    .filterNot(storage::isRuntimeKey)
                    .forEach(::add)
            }

            requireNotNull(BackGestureSettingsSnapshot.read(restored + mapOf(
                BackGestureSettingsSnapshot.REVISION to nativeRevision, "prefs_epoch" to epoch))) { "Invalid back gesture settings in backup" }
            val committed = storage.commit(nativeReset, restored) {
                currentKeys.forEach(::remove)
                restored.forEach { (key, value) -> putPreferenceValue(key, value) }
                putLong("prefs_epoch", epoch)
                putLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, nativeRevision)
            }
            if (!committed) {
                // The serialized transaction restores the captured map on rejection.
                storage.invalidate()
                error("Unable to write restored settings")
            }

            runCatching {
                storage.localSourcePrefs?.edit(commit = true) {
                    currentKeys.forEach(::remove)
                    restored.forEach { (key, value) -> putPreferenceValue(key, value) }
                    putLong("prefs_epoch", epoch)
                    putLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, nativeRevision)
                }
            }
            val cache = storage.cache()
            runCatching {
                cache?.edit(commit = true) {
                    clear()
                    restored.forEach { (key, value) -> putPreferenceValue(key, value) }
                    putLong("prefs_epoch", epoch)
                }
            }
            storage.invalidate()
            return restored.size
        }
    }

}
