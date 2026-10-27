package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.hook.logging.AppLogStore
import com.takekazex.hypertweak.hook.nativebridge.NativeRuleStateSnapshot
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupManagerTest {
    private class Storage : SettingsBackupManager.Storage {
        val remote = linkedMapOf<String, Any>("user_flag" to false, "prefs_epoch" to 3L, "debug_log" to "keep")
        val local = linkedMapOf<String, Any>("user_flag" to false)
        val events = ArrayList<String>()
        var onFlush: () -> Unit = {}
        var cacheReads = 0
        var remoteReadable = true
        var failRemoteSnapshot = false
        var failRemoteEpoch = false
        override val logLock = Any()
        override val settingsMutationLock = Any()
        override var isInitialized = true
        override val canReadRemote get() = isInitialized && remoteReadable
        override val remotePrefs = readOnly(remote)
        override var localSourcePrefs: SharedPreferences? = readOnly(local)
        override fun cache(): SharedPreferences? { cacheReads++; error("Rejected writes must not reach the cache") }
        override fun invalidate() { events += "invalidate" }
        override fun flush() { events += "flush"; onFlush() }
        override fun isRuntimeKey(key: String) = key == "prefs_epoch" || AppLogStore.isRuntimeKey(key)
        override fun commit(snapshot: NativeRuleStateSnapshot, replacement: Map<String, *>, edit: SharedPreferences.Editor.() -> Unit): Boolean {
            events += "commit"
            assertFalse(replacement.containsKey("debug_log"))
            return false
        }
        private fun readOnly(values: Map<String, Any>): SharedPreferences = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> { check(values !== remote || !failRemoteSnapshot); HashMap(values) }
                "getLong" -> { check(!failRemoteEpoch); values[args!![0]] as? Long ?: args[1] }
                else -> error("Unexpected storage access outside acknowledged writer: ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun rejectedResetAndRestoreDoNotPublishToLocalMirrorsOrCache() {
        val storage = Storage()
        val manager = SettingsBackupManager(storage)
        val remoteBefore = HashMap(storage.remote)
        val localBefore = HashMap(storage.local)
        assertFalse(manager.clearAllSettings())
        try {
            manager.restoreSettings(SettingsBackupCodec.encode(mapOf("user_flag" to true, "debug_log" to "foreign")))
            fail("Rejected restoration must be reported")
        } catch (_: IllegalStateException) { }
        assertEquals(remoteBefore, storage.remote)
        assertEquals(localBefore, storage.local)
        assertEquals(0, storage.cacheReads)
        assertEquals(listOf("invalidate", "commit", "flush", "commit", "invalidate"), storage.events)
    }

    @Test fun exportDrainsPendingWritesBeforeReadingItsPortableSnapshot() {
        val storage = Storage()
        storage.onFlush = { storage.remote["user_flag"] = true }
        val values = SettingsBackupCodec.decode(SettingsBackupManager(storage).exportSettings())
        assertEquals(mapOf("user_flag" to true), values)
        assertEquals(listOf("flush"), storage.events)
    }

    @Test fun exportUsesLocalSourceWhenRemoteIsNotInitialized() {
        val storage = Storage().apply { isInitialized = false; local["local_only"] = 7L }
        assertEquals(storage.local, SettingsBackupCodec.decode(SettingsBackupManager(storage).exportSettings()))
    }

    @Test fun malformedRestoreDoesNotFlushOrMutateStorage() {
        val storage = Storage()
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupManager(storage).restoreSettings(
                """{"format":"hypertweak-settings","format_version":2,"settings":{"int":{"user_flag":1.5}}}""")
        }
        assertTrue(storage.events.isEmpty())
        assertEquals(false, storage.remote["user_flag"])
        assertEquals(0, storage.cacheReads)
    }

    @Test fun unreadableAndUnavailableBackendsUseLabeledLocalSnapshot() {
        for (failRead in listOf(false, true)) {
            val storage = Storage().apply {
                failRemoteSnapshot = failRead
                remoteReadable = failRead
                local["user_flag"] = true
            }
            val json = SettingsBackupManager(storage).exportSettings()
            assertEquals(storage.local, SettingsBackupCodec.decode(json))
            assertEquals("local", org.json.JSONObject(json).getJSONObject("metadata").getString("snapshot_source"))
        }
    }

    @Test fun missingSourceAndQueueFailureCannotProduceSuccessfulEmptyBackup() {
        val storage = Storage().apply { isInitialized = false; localSourcePrefs = null }
        assertThrows(IllegalStateException::class.java) { SettingsBackupManager(storage).exportSettings() }
        val blocked = Storage().apply { onFlush = { throw java.util.concurrent.TimeoutException("blocked") } }
        assertThrows(java.util.concurrent.TimeoutException::class.java) { SettingsBackupManager(blocked).exportSettings() }
        assertEquals(listOf("flush"), blocked.events)
    }

    @Test fun unreadableReplacementBaselineAndInvalidEpochAbortBeforeCommit() {
        val backup = SettingsBackupCodec.encode(mapOf("user_flag" to true))
        for (storage in listOf(
            Storage().apply { failRemoteSnapshot = true },
            Storage().apply { failRemoteEpoch = true },
            Storage().apply { remote["prefs_epoch"] = Long.MAX_VALUE },
            Storage().apply { remote["prefs_epoch"] = -1L }
        )) {
            assertThrows(IllegalStateException::class.java) { SettingsBackupManager(storage).restoreSettings(backup) }
            assertEquals(listOf("flush"), storage.events)
            assertEquals(false, storage.remote["user_flag"])
            assertEquals(0, storage.cacheReads)
        }
    }
}
