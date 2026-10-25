package com.takekazex.hypertweak.app

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppActivationCoordinatorTest {
    private class Store {
        val values = mutableMapOf<String, Boolean>()
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args!![0])
                "getBoolean" -> values[args!![0]] ?: args[1]
                "edit" -> {
                    lateinit var editor: SharedPreferences.Editor
                    editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, m, a ->
                        when (m.name) {
                            "putBoolean" -> { values[a!![0] as String] = a[1] as Boolean; editor }
                            "apply" -> null
                            else -> error(m.name)
                        }
                    } as SharedPreferences.Editor
                    editor
                }
                else -> error(method.name)
            }
        } as SharedPreferences
    }
    private class Settings : AppActivationCoordinator.Settings {
        override var initialized = true
        var remote = true; var accepted = true; var resets = 0
        override fun hasRemoteConfig() = remote
        override fun clear(): Boolean { resets++; return accepted }
    }
    @Test fun reinstallResetsBeforeCachingActivationAndOnlyAfterAcknowledgement() = runBlocking {
        val store = Store(); val settings = Settings(); var reloads = 0
        val coordinator = AppActivationCoordinator(store.prefs, { false }, { reloads++ }, settings,
            serviceAvailable = { false }, awaitService = {}, refreshTargets = {})
        assertFalse(coordinator.initialActive)
        settings.accepted = false
        assertEquals(true, coordinator.refresh(true))
        assertFalse(store.values.containsKey("last_known_module_activated"))
        assertFalse(store.values.containsKey("first_run_token"))
        settings.accepted = true
        assertEquals(true, coordinator.refresh(true))
        assertEquals(2, settings.resets)
        assertEquals(true, store.values["first_run_token"])
        assertEquals(true, store.values["last_known_module_activated"])
        coordinator.refresh(true)
        assertEquals(2, settings.resets); assertEquals(3, reloads)
    }
    @Test fun ordinaryUpgradeKeepsRemoteUserSettingsWithoutAnOldFirstRunToken() = runBlocking {
        val store = Store(); store.values["last_known_module_activated"] = true
        val settings = Settings()
        val coordinator = AppActivationCoordinator(store.prefs, { true }, {}, settings, refreshTargets = {})
        assertTrue(coordinator.initialActive)
        assertEquals(true, coordinator.refresh(false))
        assertEquals(0, settings.resets)
        assertEquals(true, store.values["first_run_token"])
    }
    @Test fun initialServiceTimeoutKeepsFreshInstallEvidenceAndLateBindingKeepsTheUiState() = runBlocking {
        val store = Store(); val settings = Settings(); var bound = false
        val coordinator = AppActivationCoordinator(store.prefs, { false }, {}, settings,
            serviceAvailable = { bound }, awaitService = {}, refreshTargets = {})
        assertEquals(false, coordinator.refresh(false))
        assertFalse(store.values.containsKey("last_known_module_activated"))
        bound = true
        assertNull(coordinator.refresh(false))
        assertEquals(true, coordinator.refresh(true))
        assertEquals(1, settings.resets)
    }
    @Test fun unreadyPreferencesDoNotLoseTheRetryAcrossCoordinatorReplacement() = runBlocking {
        val store = Store(); val settings = Settings(); settings.initialized = false
        fun coordinator() = AppActivationCoordinator(store.prefs, { false }, {}, settings, refreshTargets = {})
        coordinator().refresh(true)
        assertFalse(store.values.containsKey("last_known_module_activated"))
        settings.initialized = true
        coordinator().refresh(true)
        assertEquals(1, settings.resets)
    }
}
