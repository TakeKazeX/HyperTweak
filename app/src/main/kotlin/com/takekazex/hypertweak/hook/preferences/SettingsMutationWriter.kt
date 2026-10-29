@file:Suppress("UseKtx", "ApplySharedPref")

package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys

import com.takekazex.hypertweak.hook.logging.AppLogStore
import com.takekazex.hypertweak.hook.nativebridge.NativeRuleMutation
import com.takekazex.hypertweak.hook.nativebridge.NativeRuleStateSnapshot
import com.takekazex.hypertweak.hook.nativebridge.NativeSnapshotLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSignal
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import android.content.SharedPreferences
import androidx.core.content.edit
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.SettingsDiagnostics
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Owns serialized write transactions; reads and compatibility projections stay outside. */
internal class SettingsMutationWriter(private val storage: Storage) {
    interface Storage {
        val settingsMutationLock: Any
        val isInitialized: Boolean
        val remotePrefs: SharedPreferences
        val localSourcePrefs: SharedPreferences?
        val isLocalOnly: Boolean
        fun invalidate(key: String)
        val serializedWriter: ExecutorService
    }
    private val settingsMutationLock get() = storage.settingsMutationLock
    private val isInitialized get() = storage.isInitialized
    private val remotePrefs get() = storage.remotePrefs
    private val localSourcePrefs get() = storage.localSourcePrefs
    private val isLocalOnly get() = storage.isLocalOnly
    private val serializedWriter get() = storage.serializedWriter
    private fun appLocalStore() = localSourcePrefs
    private companion object {
        const val KEY_PREFS_EPOCH = "prefs_epoch"
        const val INITIAL_EPOCH = 0L
    }

    private val nativeMirrorLock = Any()

    data class Receipt(val values: Map<String, *>, val revision: Long, val localMirror: Boolean)

    /** Backup snapshots must fail on queue timeout instead of reporting stale data as current. */
    fun awaitPendingWrites() {
        if (isInitialized && !isLocalOnly && localSourcePrefs !== remotePrefs) {
            AcknowledgedSettingsTask.run(serializedWriter) { Unit }
        }
    }

    /** External edits share the UI writer, acknowledgement and rollback boundary. */
    fun inspect(): Receipt = synchronized(settingsMutationLock) {
        check(isInitialized && !isLocalOnly) { "BACKEND_UNAVAILABLE" }
        AcknowledgedSettingsTask.run(serializedWriter) {
            check(isInitialized && !isLocalOnly) { "BACKEND_UNAVAILABLE" }
            val backend = remotePrefs
            check(backend !== localSourcePrefs) { "BACKEND_UNAVAILABLE" }
            val snapshot = RuntimeSettingsSignal.lastAcknowledged() ?: error("BACKEND_UNAVAILABLE")
            Receipt(committedValues(snapshot, backend), snapshot.revision, true)
        }
    }

    private fun committedValues(snapshot: RuntimeSettingsSnapshot, backend: SharedPreferences): Map<String, *> =
        backend.all.filterKeys { !RuntimeSettingsSnapshot.owns(it) } + snapshot.values

    fun patch(changes: Map<String, Any>, remove: Set<String>, expectedRevision: Long?,
        authorize: () -> Unit): Receipt = synchronized(settingsMutationLock) {
        check(isInitialized && !isLocalOnly) { "BACKEND_UNAVAILABLE" }
        AcknowledgedSettingsTask.run(serializedWriter) {
            authorize()
            check(isInitialized && !isLocalOnly) { "BACKEND_UNAVAILABLE" }
            val backend = remotePrefs
            check(backend !== localSourcePrefs) { "BACKEND_UNAVAILABLE" }
            val before = RuntimeSettingsSignal.lastAcknowledged() ?: error("BACKEND_UNAVAILABLE")
            check(expectedRevision == null || expectedRevision == before.revision) { "REVISION_CONFLICT" }
            val old = committedValues(before, backend)
            val next = (old - remove + changes).toMutableMap()
            val nativeKeys = NativeRuleStateSnapshot(false, 3, false).settingsValues().keys -
                setOf("prefs_epoch", PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION)
            if ((changes.keys + remove).any { it in nativeKeys }) {
                next[PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION] = maxOf(
                    (old[PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION] as? Long ?: 0) + 1,
                    android.os.SystemClock.elapsedRealtimeNanos())
            }
            requireNotNull(NativeRuleStateSnapshot.read { next }) { "INVALID_NATIVE_SETTINGS" }
            val runtime = RuntimeSettingsSignal.prepareReplacement(next)
            authorize()
            check(!isLocalOnly && backend === remotePrefs) { "BACKEND_CHANGED" }
            try {
                val editor = backend.edit()
                remove.forEach(editor::remove)
                changes.forEach { (key, value) -> editor.putPreferenceValue(key, value) }
                next[PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION]?.let {
                    editor.putPreferenceValue(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, it)
                }
                RuntimeSettingsSignal.write(editor, runtime)
                check(editor.commit()) { "COMMIT_REJECTED" }
            } catch (error: Exception) {
                // RemotePreferences may expose a rejected editor's shadow values.
                runCatching {
                    val rollback = backend.edit().clear()
                    old.forEach { (key, value) -> if (value != null) rollback.putPreferenceValue(key, value) }
                    RuntimeSettingsSignal.write(rollback, before, complete = true)
                    check(rollback.commit()) { "ROLLBACK_REJECTED" }
                }.onFailure { SettingsDiagnostics.e("DebugSettings", "rollback failed", it) }
                throw error
            }
            RuntimeSettingsSignal.acknowledge(runtime)
            val mirrored = runCatching {
                localSourcePrefs?.takeIf { it !== remotePrefs }?.let { local ->
                    val editor = local.edit()
                    remove.forEach(editor::remove)
                    changes.forEach { (key, value) -> editor.putPreferenceValue(key, value) }
                    next[PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION]?.let {
                        editor.putPreferenceValue(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, it)
                    }
                    check(editor.commit()) { "LOCAL_MIRROR_REJECTED" }
                }
                true
            }.onFailure { SettingsDiagnostics.e("DebugSettings", "local mirror failed after remote commit", it) }.getOrDefault(false)
            (changes.keys + remove).forEach(storage::invalidate)
            Receipt(next, runtime.revision, mirrored)
        }
    }

    fun write(localKey: String? = null, nativeMutation: Pair<String, Any>? = null, runtimeMutation: Pair<String, Any>? = null, runtimeChanges: Map<String, Any> = emptyMap(), block: SharedPreferences.Editor.() -> Unit) {
        synchronized(settingsMutationLock) {
            if (localKey != null && AppLogStore.isRuntimeKey(localKey)) {
                val local = appLocalStore() ?: return
                runCatching { check(local.edit().apply(block).commit()) { "Local runtime-state commit rejected" } }
                    .onFailure { SettingsDiagnostics.e("Preferences", "local runtime-state write failed", it) }
                return
            }
            val nativeChange = nativeMutation != null
            if (!isInitialized) return
            val local = localSourcePrefs
            val revision = if (nativeChange) runCatching {
                NativeSnapshotLedger.nextRevision(maxOf(remotePrefs.getLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, 0),
                    local?.getLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, 0) ?: 0), android.os.SystemClock.elapsedRealtimeNanos())
            }.onFailure { SettingsDiagnostics.e("NativeRules", "native settings commit identity invalid", it) }.getOrNull() ?: return else 0
            runCatching { synchronized(nativeMirrorLock) {
                local?.edit { block(); if (nativeChange) putLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, revision) }
            } }
                .onFailure { SettingsDiagnostics.e("Preferences", "local settings write failed", it) }
            if (isLocalOnly || local === remotePrefs) return
            serializedWriter.execute {
                // Synchronous commit: libxposed's RemotePreferences Editor.apply() is asynchronous on
                // its own executor, so a process killed right after a setting change (e.g. the in-page
                // "Restart SystemUI" action or the generic restart dialog) could die before the daemon
                // write lands — and Preferences.flush() only drains this queue, not libxposed's. A
                // blocking commit makes flush() honest: once this queue is drained, every setting that
                // was written is already visible to the hooked processes that read the daemon copy.
                runCatching {
                    val snapshot = nativeMutation?.let { mutation ->
                        val baseline = RuntimeSettingsSignal.lastAcknowledged()?.values?.let { values -> NativeRuleStateSnapshot.read { values } }
                            ?: error("native settings baseline unavailable")
                        NativeRuleMutation.apply(baseline, mutation.first, mutation.second, revision)
                    }
                    val changes = runtimeMutation?.let { mapOf(it) }.orEmpty() + runtimeChanges + snapshot?.settingsValues().orEmpty()
                    val runtime = if (changes.isNotEmpty()) RuntimeSettingsSignal.prepare(changes)
                        else RuntimeSettingsSignal.lastAcknowledged()
                    val editor = remotePrefs.edit()
                    block(editor)
                    if (runtime != null) RuntimeSettingsSignal.write(editor, runtime)
                    // The native subset travels inside the acknowledged snapshot above
                    // (NativeRuleStateSnapshot.settingsValues is merged into `changes`), so the
                    // delta plan already repairs a rejected editor's shadow values. Rewriting the
                    // subset here as well only inflated every callback by the full native payload.
                    val committed = editor.commit()
                    if (committed && changes.isNotEmpty() && runtime != null) RuntimeSettingsSignal.acknowledge(runtime)
                    if (!committed) {
                        SettingsDiagnostics.w("Preferences", "remote pref commit rejected by daemon (settings not synced)")
                    } else if (snapshot != null) {
                        // Do not overwrite newer app-local edits already queued behind this one.
                        synchronized(nativeMirrorLock) {
                            if (local != null && NativeRuleMutation.canMirror(snapshot,
                                    local.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH),
                                    local.getLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, 0))) {
                                local.edit {
                                    putBoolean(PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON, snapshot.hidden)
                                    putInt(PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS, snapshot.columns)
                                    putBoolean(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS, snapshot.contextualSearch)
                                    putBoolean(PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT, snapshot.assistantWidgets)
                                    putBoolean(PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC, snapshot.recentsHaptic)
                                    putBoolean(PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW, snapshot.folderStack)
                                    putBoolean(PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK, snapshot.widgetStack)
                                    putBoolean(PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS, snapshot.xiaoaiOptions)
                                    putInt(LauncherDesktopKeys.KEY_GRID, snapshot.desktopGrid)
                                    putInt(LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT, snapshot.iconPercent)
                                    putLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, snapshot.revision)
                                    putLong(KEY_PREFS_EPOCH, snapshot.epoch)
                                }
                            }
                        }
                    }
                }.onFailure { t ->
                    SettingsDiagnostics.w("Preferences", "remote pref write failed; retrying on next write", t)
                }
            }
        }
    }

    /** Applies one remote mutation after all previously queued writes and waits for its result.
     * Blocking commit on the writer is required before an acknowledged snapshot is published. */
    @Suppress("UseKtx", "ApplySharedPref")
    fun commitRemoteMutation(nativeSnapshot: NativeRuleStateSnapshot,
        replacement: Map<String, *>, block: SharedPreferences.Editor.() -> Unit): Boolean {
        if (!isInitialized) return false
        fun commit(): Boolean {
            val previous = HashMap(remotePrefs.all.filterKeys { isLocalOnly || !AppLogStore.isRuntimeKey(it) })
            val previousRuntime = if (isLocalOnly) null else RuntimeSettingsSignal.lastAcknowledged()
            fun rollback() = runCatching {
                val editor = remotePrefs.edit().clear()
                previous.forEach { (key, value) -> if (value != null) editor.putPreferenceValue(key, value) }
                previousRuntime?.let { RuntimeSettingsSignal.write(editor, it, complete = true) }
                check(editor.commit()) { "settings mutation rollback rejected" }
            }.onFailure { SettingsDiagnostics.w("Preferences", "settings mutation rollback failed", it) }
            return try {
                val runtime = if (isLocalOnly) null else RuntimeSettingsSignal.prepareReplacement(replacement + nativeSnapshot.settingsValues())
                val editor = remotePrefs.edit()
                block(editor)
                if (!isLocalOnly) remotePrefs.all.keys.filter(AppLogStore::isRuntimeKey).forEach(editor::remove)
                runtime?.let { RuntimeSettingsSignal.write(editor, it, complete = true) }
                editor.commit().also { committed ->
                    if (!committed) { SettingsDiagnostics.w("Preferences", "settings mutation commit rejected"); rollback() }
                    else if (runtime != null) RuntimeSettingsSignal.acknowledge(runtime)
                }
            } catch (error: Exception) {
                SettingsDiagnostics.e("Preferences", "settings mutation failed", error)
                rollback()
                false
            }
        }
        if (isLocalOnly || localSourcePrefs === remotePrefs) return commit()
        return runCatching { AcknowledgedSettingsTask.run(serializedWriter) { commit() } }
            .onFailure { SettingsDiagnostics.e("Preferences", "waiting for settings mutation failed", it) }.getOrDefault(false)
    }

    /** Atomically commits the complete Dock configuration; only an acknowledgement updates the mirror. */
    fun commitDockBackground(value: String): Boolean = synchronized(settingsMutationLock) {
        if (com.takekazex.hypertweak.settings.contract.DockConfig.decode(value) == null || !isInitialized) return false
        val key = com.takekazex.hypertweak.settings.contract.DockConfig.KEY
        fun commit(): Boolean {
            val previousRuntime = if (isLocalOnly) null else RuntimeSettingsSignal.lastAcknowledged()
            val previous = if (previousRuntime != null) previousRuntime.values[key] as? String else remotePrefs.getString(key, null)
            return try {
                val runtime = if (isLocalOnly) null else RuntimeSettingsSignal.prepare(key, value)
                val editor = remotePrefs.edit().putString(key, value)
                runtime?.let { RuntimeSettingsSignal.write(editor, it) }
                if (!editor.commit()) {
                    val restore = remotePrefs.edit()
                    if (previous == null) restore.remove(key) else restore.putString(key, previous)
                    previousRuntime?.let { RuntimeSettingsSignal.write(restore, it) }
                    check(restore.commit()) { "Dock settings rollback rejected" }
                    false
                } else {
                    localSourcePrefs?.takeIf { it !== remotePrefs }?.edit(commit = true) { putString(key, value) }
                    if (runtime != null) RuntimeSettingsSignal.acknowledge(runtime)
                    storage.invalidate(key)
                    SettingsDiagnostics.i("DockBackground", "settings committed $value")
                    true
                }
            } catch (error: Exception) {
                runCatching {
                    val restore = remotePrefs.edit()
                    if (previous == null) restore.remove(key) else restore.putString(key, previous)
                    previousRuntime?.let { RuntimeSettingsSignal.write(restore, it) }
                    check(restore.commit()) { "Dock settings rollback rejected" }
                }.onFailure { SettingsDiagnostics.w("DockBackground", "settings rollback failed", it) }
                SettingsDiagnostics.w("DockBackground", "settings commit failed", error)
                false
            }
        }
        return if (isLocalOnly || localSourcePrefs === remotePrefs) commit()
        else runCatching { AcknowledgedSettingsTask.run(serializedWriter) { commit() } }
            .onFailure { SettingsDiagnostics.w("DockBackground", "settings acknowledgement failed", it) }.getOrDefault(false)
    }

    /**
     * Writes a string and waits for the remote preferences commit to finish. This is used for
     * state that is immediately followed by restarting a hooked process, where an asynchronous
     * daemon write can otherwise race process startup.
     */
    fun putStringSynchronous(key: String, value: String) {
        storage.invalidate(key)
        if (AppLogStore.isRuntimeKey(key)) {
            write(localKey = key) { putString(key, value) }
            return
        }
        if (!isInitialized) return
        val local = localSourcePrefs
        runCatching { local?.edit(commit = true) { putString(key, value) } }
        if (isLocalOnly || local === remotePrefs) return
        val task = serializedWriter.submit {
            runCatching {
                val runtime = if (RuntimeSettingsSnapshot.owns(key)) RuntimeSettingsSignal.prepare(key, value)
                    else RuntimeSettingsSignal.lastAcknowledged()
                val editor = remotePrefs.edit().putString(key, value)
                if (runtime != null) RuntimeSettingsSignal.write(editor, runtime)
                val committed = editor.commit()
                if (committed && runtime != null && RuntimeSettingsSnapshot.owns(key)) RuntimeSettingsSignal.acknowledge(runtime)
            }.onFailure { t ->
                SettingsDiagnostics.w("Preferences", "synchronous remote pref write failed", t)
            }
        }
        runCatching { task.get(3, TimeUnit.SECONDS) }
    }

}
