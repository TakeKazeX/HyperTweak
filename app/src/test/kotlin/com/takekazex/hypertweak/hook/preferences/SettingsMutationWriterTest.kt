package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.hook.MutationTestPreferences
import com.takekazex.hypertweak.hook.logging.AppLogStore
import com.takekazex.hypertweak.hook.nativebridge.NativeRuleStateSnapshot
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSignal
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SettingsMutationWriterTest {
    private class Storage(val local: MutationTestPreferences = MutationTestPreferences(), val remote: MutationTestPreferences = MutationTestPreferences(), override var isLocalOnly: Boolean = false) : SettingsMutationWriter.Storage {
        override val settingsMutationLock = Any()
        override var isInitialized = true
        override val remotePrefs = remote.prefs
        override val localSourcePrefs = local.prefs
        override val serializedWriter = Executors.newFixedThreadPool(1)
        val invalidated = mutableListOf<String>()
        override fun invalidate(key: String) { invalidated += key }
        fun drain() { serializedWriter.submit {}.get(5, TimeUnit.SECONDS) }
    }
    private fun isolated(localOnly: Boolean = false, block: (Storage, SettingsMutationWriter) -> Unit) {
        val storage = Storage(isLocalOnly = localOnly)
        val fields = listOf("source", "acknowledged").map { RuntimeSettingsSignal::class.java.getDeclaredField(it).apply { isAccessible = true } }
        val saved = fields.map { it.get(RuntimeSettingsSignal) }
        try {
            fields[0].set(RuntimeSettingsSignal, storage.remote.prefs)
            fields[1].set(RuntimeSettingsSignal, RuntimeSettingsSnapshot.read(10L, emptyMap<String, Any>())!!)
            block(storage, SettingsMutationWriter(storage))
            storage.drain()
        } finally {
            storage.serializedWriter.shutdown()
            check(storage.serializedWriter.awaitTermination(5, TimeUnit.SECONDS))
            fields.zip(saved).forEach { (field, value) -> field.set(RuntimeSettingsSignal, value) }
        }
    }
    @Test fun ordinaryWritesAreQueuedAndAcknowledgedInOrder() = isolated { s, writer ->
        val key = SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE
        writer.write(runtimeMutation = key to true) { putBoolean(key, true) }
        writer.write(runtimeMutation = key to false) { putBoolean(key, false) }
        s.drain()
        assertEquals(false, s.local.persisted[key])
        assertEquals(false, s.remote.persisted[key])
        assertEquals(false, RuntimeSettingsSignal.lastAcknowledged()!!.values[key])
        assertEquals(12L, RuntimeSettingsSignal.lastAcknowledged()!!.revision)
    }

    @Test fun backupBarrierWaitsForEarlierRemoteWrites() = isolated { s, writer ->
        writer.write { putString("backup_value", "latest") }
        writer.awaitPendingWrites()
        assertEquals("latest", s.remote.persisted["backup_value"])
    }

    @Test fun backupBarrierReportsRejectedQueue() {
        val storage = Storage()
        storage.serializedWriter.shutdown()
        assertThrows(java.util.concurrent.RejectedExecutionException::class.java) {
            SettingsMutationWriter(storage).awaitPendingWrites()
        }
    }
    @Test fun rejectedReplacementRepairsDaemonShadowAndKeepsAcknowledgement() = isolated { s, writer ->
        s.remote.values["ordinary"] = "before"
        s.remote.persisted["ordinary"] = "before"
        s.remote.shadowBeforeAcknowledgement = true
        s.remote.rejectNext = true
        val before = RuntimeSettingsSignal.lastAcknowledged()
        assertFalse(writer.commitRemoteMutation(NativeRuleStateSnapshot(false, 3, false, epoch = 1), emptyMap<String, Any>()) { clear(); putString("ordinary", "after") })
        assertEquals("before", s.remote.values["ordinary"])
        assertEquals("before", s.remote.persisted["ordinary"])
        assertEquals(before, RuntimeSettingsSignal.lastAcknowledged())
        assertEquals(2, s.remote.commits)
    }
    @Test fun acceptedReplacementAdvancesEpochAndClearsOldValues() = isolated { s, writer ->
        s.remote.values["obsolete"] = true
        for (epoch in 1L..2L) {
            val snapshot = NativeRuleStateSnapshot(false, 3, false, revision = epoch, epoch = epoch)
            assertTrue(writer.commitRemoteMutation(snapshot, emptyMap<String, Any>()) { clear(); putLong("prefs_epoch", epoch) })
            assertEquals(epoch, s.remote.persisted["prefs_epoch"])
            assertEquals(epoch, RuntimeSettingsSignal.lastAcknowledged()!!.values["prefs_epoch"])
        }
        assertFalse(s.remote.values.containsKey("obsolete"))
    }
    @Test fun nativeAcknowledgementRepairsOnlyMatchingLocalGeneration() = isolated { s, writer ->
        val key = PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON
        writer.write(nativeMutation = key to true) { putBoolean(key, true) }
        s.drain()
        assertEquals(true, s.remote.persisted[key])
        assertEquals(true, s.local.persisted[key])
        assertEquals(3, s.local.persisted[PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS])
        assertEquals(s.remote.persisted[PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION], s.local.persisted[PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION])
        assertEquals(0L, s.local.persisted["prefs_epoch"])
    }
    @Test fun rejectedNativeWriteIsNotAcknowledgedAndNextWriteRepairsShadow() = isolated { s, writer ->
        val key = PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON
        s.remote.shadowBeforeAcknowledgement = true
        s.remote.rejectNext = true
        val before = RuntimeSettingsSignal.lastAcknowledged()
        writer.write(nativeMutation = key to true) { putBoolean(key, true) }
        s.drain()
        assertEquals(before, RuntimeSettingsSignal.lastAcknowledged())
        writer.write(nativeMutation = PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS to 4) { putInt(PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS, 4) }
        s.drain()
        assertEquals(false, s.remote.persisted[key])
        assertEquals(false, s.local.persisted[key])
        assertEquals(4, s.local.persisted[PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS])
    }
    @Test fun localOnlyAndDiagnosticsNeverUseRemoteQueue() = isolated(localOnly = true) { s, writer ->
        writer.write { putBoolean("setting", true) }
        writer.write(localKey = "debug_log_session") { putString("debug_log_session", "session") }
        writer.putStringSynchronous("restart", "ready")
        assertEquals(true, s.local.persisted["setting"])
        assertEquals("session", s.local.persisted["debug_log_session"])
        assertEquals("ready", s.local.persisted["restart"])
        assertEquals(0, s.remote.commits)
        assertEquals(0, s.remote.reads)
    }
    @Test fun unavailableStorageRejectsOrdinaryAndBulkWrites() = isolated { s, writer ->
        s.isInitialized = false
        writer.write { putBoolean("setting", true) }
        assertFalse(writer.commitRemoteMutation(NativeRuleStateSnapshot(false, 3, false), emptyMap<String, Any>()) { clear() })
        assertEquals(0, s.remote.commits)
        assertEquals(0, s.local.commits)
    }
    @Test fun newerLocalEditsAreNotOverwrittenByAnOlderAcknowledgement() = isolated { s, writer ->
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        s.serializedWriter.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        check(entered.await(5, TimeUnit.SECONDS))
        try {
            writer.write(nativeMutation = PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON to true) { putBoolean(PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON, true) }
            // An epoch reset while the first native commit is queued must fence its mirror.
            s.local.prefs.edit().putLong("prefs_epoch", 7).putInt(PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS, 5).commit()
        } finally { release.countDown() }
        s.drain()
        assertEquals(7L, s.local.persisted["prefs_epoch"])
        assertEquals(5, s.local.persisted[PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS])
        assertEquals(0L, RuntimeSettingsSignal.lastAcknowledged()!!.values["prefs_epoch"])
    }
    @Test fun dockCommitValidatesRollsBackAndUpdatesMirrorOnlyAfterAcknowledgement() = isolated { s, writer ->
        val key = com.takekazex.hypertweak.settings.contract.DockConfig.KEY
        val old = com.takekazex.hypertweak.settings.contract.DockConfig().encode()
        val next = com.takekazex.hypertweak.settings.contract.DockConfig(enabled = true).encode()
        assertFalse(writer.commitDockBackground("invalid"))
        assertEquals(0, s.remote.commits)
        assertTrue(writer.commitDockBackground(old))
        s.remote.shadowBeforeAcknowledgement = true
        s.remote.rejectNext = true
        assertFalse(writer.commitDockBackground(next))
        assertEquals(old, s.remote.persisted[key])
        assertEquals(old, s.remote.values[key])
        assertEquals(old, s.local.persisted[key])
        assertTrue(writer.commitDockBackground(next))
        assertEquals(next, s.remote.persisted[key])
        assertEquals(next, s.local.persisted[key])
        assertEquals(listOf(key, key), s.invalidated)
    }

    @Test fun queuedDockCommitAndBulkReplacementCannotWaitOnEachOthersLocks() = isolated { s, writer ->
        val release = java.util.concurrent.CountDownLatch(1)
        val bulkAttempt = java.util.concurrent.CountDownLatch(1)
        val dockResult = java.util.concurrent.atomic.AtomicBoolean()
        val bulkResult = java.util.concurrent.atomic.AtomicBoolean()
        s.serializedWriter.execute { release.await() }
        val dock = Thread {
            dockResult.set(writer.commitDockBackground(com.takekazex.hypertweak.settings.contract.DockConfig().encode()))
        }
        val bulk = Thread {
            bulkAttempt.countDown()
            synchronized(s.settingsMutationLock) {
                bulkResult.set(writer.commitRemoteMutation(NativeRuleStateSnapshot(false, 3, false, epoch = 1),
                    emptyMap<String, Any>()) { clear(); putLong("prefs_epoch", 1) })
            }
        }
        try {
            dock.start()
            val queue = (s.serializedWriter as java.util.concurrent.ThreadPoolExecutor).queue
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (queue.isEmpty() && System.nanoTime() < deadline) Thread.yield()
            assertFalse("Dock transaction must be queued", queue.isEmpty())
            bulk.start()
            assertTrue(bulkAttempt.await(2, TimeUnit.SECONDS))
            val waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (bulk.state == Thread.State.RUNNABLE && System.nanoTime() < waitDeadline) Thread.yield()
            release.countDown()
            dock.join(2_000)
            bulk.join(2_000)
            assertFalse("Dock/bulk lock cycle", dock.isAlive || bulk.isAlive)
            assertTrue(dockResult.get())
            assertTrue(bulkResult.get())
            assertEquals(1L, s.remote.persisted["prefs_epoch"])
            assertFalse(s.remote.persisted.containsKey(com.takekazex.hypertweak.settings.contract.DockConfig.KEY))
        } finally { release.countDown(); dock.join(10_000); bulk.join(10_000) }
    }
    @Test fun synchronousStringWaitsForEarlierWritesAndRemotePersistence() = isolated { s, writer ->
        writer.write { putString("order", "first") }
        writer.putStringSynchronous("order", "second")
        assertEquals("second", s.remote.persisted["order"])
        assertEquals("second", s.local.persisted["order"])
        assertEquals(listOf("order"), s.invalidated)
    }

    @Test fun resetAndRestoreAdvanceEpochOnlyAfterTheRealWriterAcknowledges() = isolated { s, writer ->
        val cache = MutationTestPreferences()
        val backups = SettingsBackupManager(object : SettingsBackupManager.Storage {
            override val logLock = Any()
            override val settingsMutationLock = s.settingsMutationLock
            override val isInitialized get() = s.isInitialized
            override val remotePrefs = s.remotePrefs
            override val localSourcePrefs = s.localSourcePrefs
            override fun cache() = cache.prefs
            override fun invalidate() = Unit
            override fun flush() = s.drain()
            override fun isRuntimeKey(key: String) = key == "prefs_epoch" || AppLogStore.isRuntimeKey(key)
            override fun commit(snapshot: NativeRuleStateSnapshot, replacement: Map<String, *>, edit: android.content.SharedPreferences.Editor.() -> Unit) = writer.commitRemoteMutation(snapshot, replacement, edit)
        })
        assertTrue(backups.clearAllSettings())
        assertEquals(1L, s.remote.persisted["prefs_epoch"])
        assertEquals(1L, s.local.persisted["prefs_epoch"])
        assertEquals(1L, cache.persisted["prefs_epoch"])
        val key = SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE
        val backup = SettingsBackupCodec.encode(mapOf(key to true))
        s.remote.shadowBeforeAcknowledgement = true
        s.remote.rejectNext = true
        assertThrows(IllegalStateException::class.java) { backups.restoreSettings(backup) }
        assertEquals(1L, s.remote.persisted["prefs_epoch"])
        assertEquals(1L, s.local.persisted["prefs_epoch"])
        assertEquals(1L, cache.persisted["prefs_epoch"])
        assertEquals(1, backups.restoreSettings(backup))
        assertEquals(2L, s.remote.persisted["prefs_epoch"])
        assertEquals(2L, s.local.persisted["prefs_epoch"])
        assertEquals(2L, cache.persisted["prefs_epoch"])
        assertEquals(true, s.remote.persisted[key])
        assertEquals(true, cache.persisted[key])
    }

    @Test fun debugPatchCommitsAndResetRemovesExplicitValues() = isolated { s, writer ->
        val key = SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE
        val first = writer.patch(mapOf(key to true), emptySet(), 10L) { }
        assertEquals(true, s.remote.persisted[key])
        assertEquals(true, s.local.persisted[key])
        assertTrue(first.revision > 10)
        val reset = writer.patch(emptyMap(), setOf(key), first.revision) { }
        assertFalse(s.remote.persisted.containsKey(key))
        assertFalse(s.local.persisted.containsKey(key))
        assertFalse(reset.values.containsKey(key))
        assertFalse(RuntimeSettingsSignal.lastAcknowledged()!!.values.containsKey(key))
    }
    @Test fun debugPatchRejectsConflictAndExpiredAuthorizationBeforeCommit() = isolated { s, writer ->
        val key = SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE
        fun rejected(block: () -> Unit) {
            try { block(); fail("must reject") } catch (_: Exception) { }
        }
        rejected { writer.patch(mapOf(key to true), emptySet(), 9L) { } }
        rejected { writer.patch(mapOf(key to true), emptySet(), 10L) { error("DEBUG_ACCESS_DISABLED") } }
        assertEquals(0, s.remote.commits)
        assertFalse(s.local.persisted.containsKey(key))
    }
    @Test fun rejectedDebugPatchRollsBackShadowAndNeverUpdatesMirror() = isolated { s, writer ->
        val key = SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE
        s.remote.shadowBeforeAcknowledgement = true
        s.remote.rejectNext = true
        try { writer.patch(mapOf(key to true), emptySet(), 10L) { }; fail("must reject") }
        catch (_: Exception) { }
        assertFalse(s.remote.persisted.containsKey(key))
        assertFalse(s.remote.values.containsKey(key))
        assertFalse(s.local.persisted.containsKey(key))
        assertEquals(10L, RuntimeSettingsSignal.lastAcknowledged()!!.revision)
    }
    @Test fun debugPatchReportsRemoteSuccessEvenIfLocalMirrorFails() = isolated { s, writer ->
        s.local.rejectNext = true
        val receipt = writer.patch(mapOf(SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE to true), emptySet(), null) { }
        assertFalse(receipt.localMirror)
        assertEquals(true, s.remote.persisted[SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE])
    }
    @Test fun debugNativeResetAndInvalidBatchPreserveContract() = isolated { s, writer ->
        val key = PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS
        writer.patch(mapOf(key to 5), emptySet(), null) { }
        assertEquals(5, s.remote.persisted[key])
        writer.patch(emptyMap(), setOf(key), null) { }
        assertFalse(s.remote.persisted.containsKey(key))
        val before = RuntimeSettingsSignal.lastAcknowledged()
        try { writer.patch(mapOf(key to 8), emptySet(), null) { }; fail("must reject") }
        catch (_: Exception) { }
        assertEquals(before, RuntimeSettingsSignal.lastAcknowledged())
    }

    @Test fun debugReadAndPatchNeverResurrectRejectedRuntimeShadow() = isolated { s, writer ->
        val key = SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE
        // A previous failed ordinary editor exposed a value absent from the acknowledged map.
        s.remote.values[key] = true
        assertFalse(writer.inspect().values.containsKey(key))
        writer.patch(mapOf("record_logs" to true), emptySet(), null) { }
        assertFalse(RuntimeSettingsSignal.lastAcknowledged()!!.values.containsKey(key))
        assertFalse(s.remote.persisted.containsKey(key))
        assertFalse(s.remote.values.containsKey(key))
    }

    @Test fun debugPatchCannotFallBackToLocalStoreAfterQueueAdmission() = isolated { s, writer ->
        try {
            writer.patch(mapOf("record_logs" to false), emptySet(), null) { s.isLocalOnly = true }
            fail("must reject disconnected backend")
        } catch (_: Exception) { }
        assertEquals(0, s.remote.commits)
        assertEquals(0, s.local.commits)
    }

    @Test fun expiredDebugGrantBeforeCommitDoesNotEvenRunRollbackEditor() = isolated { s, writer ->
        var checks = 0
        try {
            writer.patch(mapOf("record_logs" to false), emptySet(), null) {
                if (++checks == 2) error("DEBUG_ACCESS_DISABLED")
            }
            fail("must reject expired grant")
        } catch (_: Exception) { }
        assertEquals(0, s.remote.commits)
        assertEquals(0, s.local.commits)
    }

}
