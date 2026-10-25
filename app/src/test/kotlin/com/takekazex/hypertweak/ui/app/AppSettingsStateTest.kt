package com.takekazex.hypertweak.ui.app

import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.AppSettingsStore
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import org.junit.Assert.*
import org.junit.Test

/** Only a storage capability fake; it does not simulate a host or Compose rendering. */
internal class MemoryAppSettings : AppSettingsStore {
    val values = mutableMapOf<String, Any>()
    var effectiveAodFingerprint = false
    override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
    override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
    override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun putFloat(key: String, value: Float) { values[key] = value }
    override fun flush() = Unit
    override fun clearAllSettings(): Boolean { values.clear(); return true }
    override fun hideFingerprintAodEnabled() = effectiveAodFingerprint
    override fun hideFingerprintLockscreenEnabled() = false
    override fun hideFingerprintAppAuthEnabled() = false
    override fun powerButtonAction() = 0
    override fun setPowerButtonAction(action: Int) = Unit
    override fun contextualSearchLongPress() = false
}

class AppSettingsStateTest {
    @Test fun `service rebind and reset use the same injected readers as initial composition`() {
        val store = MemoryAppSettings()
        store.putInt(AppSettingsKeys.KEY_LANGUAGE, 2)
        store.putInt(AppSettingsKeys.KEY_APP_BLUR_MODE, 99)
        val state = AppSettingsState(store)
        assertEquals(2, state.appLanguage)
        assertEquals(2, state.appBlurMode)
        assertTrue(state.removeIslandPlaceholder)
        store.putInt(AppSettingsKeys.KEY_LANGUAGE, 3)
        store.putFloat(AppSettingsKeys.KEY_PAGE_SCALE, 1.2f)
        store.effectiveAodFingerprint = true
        state.reload()
        assertEquals(3, state.appLanguage)
        assertEquals(1.2f, state.pageScale)
        assertTrue(state.hideFingerprintAod)
        store.clearAllSettings()
        state.reload()
        assertEquals(0, state.appLanguage)
        assertEquals(1, state.appBlurMode)
        assertEquals(1f, state.pageScale)
        // Effective migration readers stay authoritative even when raw settings are absent.
        assertTrue(state.hideFingerprintAod)
    }

    @Test fun `restart projection uses observable edits and reads unknown keys from the same store`() {
        val store = MemoryAppSettings()
        val state = AppSettingsState(store)
        state.threeIslands = true
        assertTrue(state.currentTweakValue(PlatformSettingsKeys.KEY_THREE_ISLANDS))
        assertFalse(store.getBoolean(PlatformSettingsKeys.KEY_THREE_ISLANDS, false))
        store.putBoolean("secondary_page_key", true)
        assertTrue(state.currentTweakValue("secondary_page_key"))
        store.putInt("secondary_selector", 7)
        assertEquals(7, state.currentTweakValueInt("secondary_selector"))
    }
}
