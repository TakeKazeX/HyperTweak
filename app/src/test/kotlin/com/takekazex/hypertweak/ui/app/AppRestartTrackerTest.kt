package com.takekazex.hypertweak.ui.app

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.restart.RestartScopeSelection
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AppRestartTrackerTest {
    private class Store : ContextWrapper(null) {
        val values = mutableMapOf<String, Any>()
        private val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor()
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args!![0])
                else -> {
                    check(method.name.startsWith("get")) { method.name }
                    values[args!![0]] ?: args[1]
                }
            }
        } as SharedPreferences
        override fun getSharedPreferences(name: String, mode: Int) = prefs
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "commit", "apply" -> {
                        pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        if (method.name == "commit") true else null
                    }
                    "remove" -> { pending[args!![0] as String] = null; editor }
                    else -> { check(method.name.startsWith("put")); pending[args!![0] as String] = args[1]; editor }
                }
            } as SharedPreferences.Editor
            return editor
        }
    }

    @Test fun revertingAToggleClearsOnlyItsDirtyRestartRequest() {
        val store = Store(); val tracker = AppRestartTracker(store, AppSettingsState(MemoryAppSettings()))
        val key = SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS
        store.values["tweak_baseline_$key"] = false
        tracker.markTweaked(key, true)
        assertEquals(setOf(key), tracker.dirtyTweakKeys)
        assertTrue(tracker.pendingRestartScopes.systemUi)
        tracker.markTweaked(key, false)
        assertTrue(tracker.dirtyTweakKeys.isEmpty())
        assertTrue(tracker.pendingRestartScopes.isEmpty())
    }

    @Test fun partialRestartKeepsManualRequestsAndAdvancesTheClearedBaseline() {
        val store = Store(); val settings = AppSettingsState(MemoryAppSettings())
        val tracker = AppRestartTracker(store, settings)
        val key = SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS
        store.values["tweak_baseline_$key"] = false
        tracker.requestRestartScopes(RestartScopeSelection(settings = true))
        tracker.markTweaked(key, true)
        settings.lockscreenKeepNotifications = true
        tracker.clearRestartedScopes(RestartScopeSelection(systemUi = true))
        assertTrue(tracker.dirtyTweakKeys.isEmpty())
        assertEquals(RestartScopeSelection(settings = true), tracker.pendingRestartScopes)
        assertEquals(true, store.values["tweak_baseline_$key"])
        tracker.markTweaked(key, false)
        assertTrue(tracker.pendingRestartScopes.systemUi)
        assertTrue(tracker.pendingRestartScopes.settings)
    }

    @Test fun liveChangesDoNotCreateDirtyBaselinesOrRestartRequests() {
        val store = Store()
        val tracker = AppRestartTracker(store, AppSettingsState(MemoryAppSettings()))
        for (key in listOf(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR,
            SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED, PlatformSettingsKeys.KEY_THREE_ISLANDS,
            PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH)) {
            tracker.markTweaked(key, true)
            assertFalse(store.values.containsKey("tweak_baseline_$key"))
        }
        assertTrue(tracker.dirtyTweakKeys.isEmpty())
        assertTrue(tracker.pendingRestartScopes.isEmpty())
    }

    @Test fun liveFingerprintSelectorIgnoresLegacyBaselineAndNeedsNoRestart() {
        val store = Store(); val settings = AppSettingsState(MemoryAppSettings())
        val tracker = AppRestartTracker(store, settings)
        val key = SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID
        // Older releases could leave either type in this retired restart slot.
        for (legacy in listOf(1, true)) {
            store.values["tweak_baseline_$key"] = legacy
            for (mode in listOf(2, 1, 0)) {
                tracker.markTweakedInt(key, mode)
                settings.lockscreenFingerprintAvoid = mode
                tracker.clearRestartedScopes(RestartScopeSelection(systemUi = true))
                assertEquals(legacy, store.values["tweak_baseline_$key"])
                assertTrue(tracker.dirtyTweakKeys.isEmpty())
                assertTrue(tracker.pendingRestartScopes.isEmpty())
            }
        }
    }

    @Test fun resetTrackingAndBootMigrationPreserveUnrelatedSettings() {
        val store = Store()
        store.values["pending_restart_boot_token"] = "previous-boot"
        store.values["manual_pending_restart_scopes"] = setOf("systemui")
        store.values["user_setting"] = "keep"
        val tracker = AppRestartTracker(store, AppSettingsState(MemoryAppSettings()))
        assertTrue(tracker.pendingRestartScopes.isEmpty())
        tracker.requestRestartScopes(RestartScopeSelection(systemUi = true))
        assertTrue(store.values.containsKey(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES))
        tracker.clearPendingRestartTracking()
        assertFalse(store.values.containsKey(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES))
        assertTrue(tracker.pendingRestartScopes.isEmpty())
        assertEquals("keep", store.values["user_setting"])
    }
}
