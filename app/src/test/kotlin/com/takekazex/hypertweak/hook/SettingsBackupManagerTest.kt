package com.takekazex.hypertweak.hook

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
        override val logLock = Any()
        override val settingsMutationLock = Any()
        override val isInitialized = true
        override val remotePrefs = readOnly(remote)
        override val localSourcePrefs = readOnly(local)
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
                "getAll" -> HashMap(values)
                "getLong" -> values[args!![0]] as? Long ?: args[1]
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
}
