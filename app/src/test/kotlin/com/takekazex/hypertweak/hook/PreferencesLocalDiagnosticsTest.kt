package com.takekazex.hypertweak.hook

import android.content.SharedPreferences
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PreferencesLocalDiagnosticsTest {
    private class Store(initial: Map<String, Any> = emptyMap()) {
        val values = initial.toMutableMap()
        val persisted = initial.toMutableMap()
        var shadowBeforeAcknowledgement = false
        var commits = 0
        var accept = true
        var reads = 0
        var failReads = false
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor()
                "equals" -> false
                "hashCode" -> System.identityHashCode(this)
                "toString" -> "TestStore"
                else -> {
                    reads++
                    check(!failReads) { "Remote must not be read" }
                    val key = args?.firstOrNull() as? String
                    when (method.name) {
                        "getAll" -> values.toMap()
                        "contains" -> values.containsKey(key)
                        else -> if (method.name.startsWith("get")) values[key] ?: args!![1]
                            else error(method.name)
                    }
                }
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            var clear = false
            lateinit var editor: SharedPreferences.Editor
            fun commit(): Boolean {
                commits++
                fun applyTo(target: MutableMap<String, Any>) {
                    if (clear) target.clear()
                    pending.forEach { (key, value) -> if (value == null) target.remove(key) else target[key] = value }
                }
                if (accept || shadowBeforeAcknowledgement) applyTo(values)
                if (!accept) return false
                applyTo(persisted)
                return true
            }
            editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "commit" -> commit()
                    "apply" -> { commit(); null }
                    "clear" -> { clear = true; editor }
                    "remove" -> { pending[args!![0] as String] = null; editor }
                    else -> { check(method.name.startsWith("put")); pending[args!![0] as String] = args[1]; editor }
                }
            } as SharedPreferences.Editor
            return editor
        }
    }

    private fun isolated(local: Store?, remote: Store, cache: Store? = null, block: () -> Unit) {
        val fields = listOf("remotePrefs", "localSourcePrefs", "localCachePrefs", "isLocalOnly", "remoteBackendUnavailable", "reportedHookLogWrite")
            .map { Preferences::class.java.getDeclaredField(it).apply { isAccessible = true } }
        val old = fields.map { it.get(Preferences) }
        try {
            fields.zip(listOf(remote.prefs, local?.prefs, cache?.prefs, false, false, false))
                .forEach { (field, value) -> field.set(Preferences, value) }
            Preferences.invalidateRuntimeReadCache()
            block()
            Preferences.flush()
        } finally {
            fields.zip(old).forEach { (field, value) -> field.set(Preferences, value) }
            Preferences.invalidateRuntimeReadCache()
        }
    }

    @Test fun `all diagnostics setters including synchronous strings stay local`() {
        val local = Store()
        val remote = Store()
        isolated(local, remote) {
            Preferences.putString("debug_log_p_app", "record")
            Preferences.putStringSynchronous("debug_log_session", "session")
            Preferences.putStringSet("debug_log_processes", setOf("app"))
            Preferences.putInt("debug_log_level_app", 3)
            Preferences.putBoolean("debug_log_level_test_bool", true)
            Preferences.putFloat("debug_log_level_test_float", 2f)
            assertEquals("record", Preferences.getString("debug_log_p_app"))
            assertEquals("session", Preferences.getString("debug_log_session"))
            assertEquals(setOf("app"), Preferences.getStringSet("debug_log_processes"))
            assertEquals(3, Preferences.getInt("debug_log_level_app"))
            assertTrue(Preferences.getBoolean("debug_log_level_test_bool"))
            assertEquals(2f, Preferences.getFloat("debug_log_level_test_float"))
            assertTrue(Preferences.contains("debug_log_session"))
            Preferences.flush()
            assertEquals(0, remote.reads)
            assertEquals(0, remote.commits)
            assertTrue(remote.values.isEmpty())
            assertEquals(6, local.commits)
        }
    }

    @Test fun `injected processes cannot persist or read app diagnostics even with a host cache`() {
        val remote = Store(mapOf("debug_log_p_app" to "legacy"))
        val cache = Store(mapOf("debug_log_p_app" to "host"))
        isolated(null, remote, cache) {
            Preferences.putString("debug_log_p_app", "forbidden")
            Preferences.putStringSynchronous("debug_log_session", "forbidden")
            Preferences.appendDebugLog("systemui", "forbidden")
            Preferences.rotateLogSessionIfNeeded("new")
            Preferences.clearDebugLog()
            assertEquals("", Preferences.getDebugLog())
            assertEquals("", Preferences.getString("debug_log_p_app"))
            assertFalse(Preferences.contains("debug_log_p_app"))
            Preferences.flush()
            assertEquals(0, remote.commits)
            assertEquals(0, remote.reads)
            assertEquals(0, cache.commits)
        }
    }

    @Test fun `cleanup preserves shared switches and revisions and is idempotent`() {
        val remote = Store(mapOf("debug_log_p_app" to "old", "debug_log" to "legacy",
            "debug_log_session" to "old-session", "debug_log_processes" to setOf("app"),
            "debug_log_level_systemui" to 3, PlatformSettingsKeys.KEY_RECORD_LOGS to false,
            AppSettingsKeys.KEY_LOG_LEVEL to 5, "runtime_settings_revision" to 12L, "prefs_epoch" to 2L))
        AppLogStore.purgeRemoteRecords(remote.prefs)
        assertEquals(mapOf(PlatformSettingsKeys.KEY_RECORD_LOGS to false, AppSettingsKeys.KEY_LOG_LEVEL to 5,
            "runtime_settings_revision" to 12L, "prefs_epoch" to 2L), remote.values)
        AppLogStore.purgeRemoteRecords(remote.prefs)
        assertEquals(1, remote.commits)
        assertFalse(AppLogStore.isRuntimeKey(PlatformSettingsKeys.KEY_RECORD_LOGS))
        assertFalse(AppLogStore.isRuntimeKey(AppSettingsKeys.KEY_LOG_LEVEL))
    }

    @Test fun `rejected cleanup is visible and can be retried`() {
        val remote = Store(mapOf("debug_log_p_app" to "old"))
        remote.accept = false
        remote.shadowBeforeAcknowledgement = true
        assertThrows(IllegalStateException::class.java) { AppLogStore.purgeRemoteRecords(remote.prefs) }
        assertFalse(remote.values.containsKey("debug_log_p_app"))
        assertEquals("old", remote.persisted["debug_log_p_app"])
        remote.accept = true
        AppLogStore.purgeRemoteRecords(remote.prefs)
        assertTrue(remote.values.isEmpty())
        assertTrue(remote.persisted.isEmpty())
        assertEquals(2, remote.commits)
        AppLogStore.purgeRemoteRecords(remote.prefs)
        assertEquals(2, remote.commits)
    }

    @Test fun `backend unavailable skips all remote typed reads until reinitialized`() {
        val values = mapOf("test_bool" to true, "test_int" to 7, "test_float" to 2f,
            "test_string" to "cached", "test_set" to setOf("cached"), AppSettingsKeys.KEY_LOG_LEVEL to 6)
        val remote = Store()
        val cache = Store(values)
        isolated(null, remote, cache) {
            Preferences.noteRemoteBackendUnavailable()
            remote.failReads = true
            assertTrue(Preferences.contains("test_bool"))
            assertTrue(Preferences.getBoolean("test_bool"))
            assertEquals(7, Preferences.getInt("test_int"))
            assertEquals(2f, Preferences.getFloat("test_float"))
            assertEquals("cached", Preferences.getString("test_string"))
            assertEquals(setOf("cached"), Preferences.getStringSet("test_set"))
            assertEquals(6, Preferences.logLevel(4))
            assertEquals(0, remote.reads)
            remote.failReads = false
            remote.values["test_int"] = 9
            Preferences.init(remote.prefs)
            assertFalse(Preferences.isRemoteBackendUnavailable())
            assertEquals(9, Preferences.getInt("test_int"))
        }
    }

    @Test fun `global log controls still commit remotely and app logs remain local`() {
        val local = Store()
        val remote = Store()
        isolated(local, remote) {
            Preferences.putBoolean(PlatformSettingsKeys.KEY_RECORD_LOGS, true)
            Preferences.putInt(AppSettingsKeys.KEY_LOG_LEVEL, 5)
            Preferences.flush()
            assertEquals(true, remote.values[PlatformSettingsKeys.KEY_RECORD_LOGS])
            assertEquals(5, remote.values[AppSettingsKeys.KEY_LOG_LEVEL])
            Preferences.appendDebugLog("app", "local record")
            assertEquals("local record", Preferences.getDebugLog())
            assertFalse(remote.values.containsKey("debug_log_p_app"))
            Preferences.clearDebugLog()
            assertEquals("", Preferences.getDebugLog())
            assertEquals(true, remote.values[PlatformSettingsKeys.KEY_RECORD_LOGS])
        }
    }

    @Test fun `early remote binding is cleaned when app local initialization arrives later`() {
        val local = Store()
        val remote = Store(mapOf("debug_log_p_app" to "legacy"))
        isolated(null, remote) {
            Preferences.init(remote.prefs)
            Preferences.flush()
            assertEquals(0, remote.commits)
            Preferences.init(local.prefs, useLocalOnly = true)
            Preferences.flush()
            assertTrue(remote.values.isEmpty())
            assertEquals(1, remote.commits)
        }
    }

    @Test fun `service binding automatically purges historical remote logs without deleting local records`() {
        val local = Store(mapOf("debug_log_p_app" to "current"))
        val remote = Store(mapOf("debug_log_p_app" to "legacy", PlatformSettingsKeys.KEY_RECORD_LOGS to true))
        isolated(local, remote) {
            Preferences.init(remote.prefs)
            Preferences.flush()
            assertEquals(mapOf(PlatformSettingsKeys.KEY_RECORD_LOGS to true), remote.values)
            assertEquals("current", local.values["debug_log_p_app"])
        }
    }
}
