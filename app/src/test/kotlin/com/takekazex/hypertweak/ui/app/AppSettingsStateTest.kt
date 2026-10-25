package com.takekazex.hypertweak.ui.app

import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.AppSettingsStore
import com.takekazex.hypertweak.settings.contract.BackupSettings
import com.takekazex.hypertweak.settings.contract.FingerprintSettings
import com.takekazex.hypertweak.settings.contract.PowerButtonSettings
import com.takekazex.hypertweak.settings.contract.BackGestureSettings
import com.takekazex.hypertweak.settings.contract.CameraAppSettings
import com.takekazex.hypertweak.settings.contract.LauncherAppSettings
import com.takekazex.hypertweak.settings.contract.SystemUiAppSettings
import com.takekazex.hypertweak.settings.contract.PlatformAppSettings
import com.takekazex.hypertweak.settings.contract.PowerSaveSettings
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
    override fun getString(key: String, default: String): String = error("Unused page operation: getString")
    override fun getStringSet(key: String, default: Set<String>): Set<String> = error("Unused page operation: getStringSet")
    override fun putString(key: String, value: String): Unit = error("Unused page operation: putString")
    override fun putStringSet(key: String, value: Set<String>): Unit = error("Unused page operation: putStringSet")
    override fun contains(key: String): Boolean = error("Unused page operation: contains")
    override val backup = object : BackupSettings {
        override fun clearAllSettings(): Boolean { values.clear(); return true }
        override fun exportSettings(): String = error("Unused page operation: exportSettings")
        override fun restoreSettings(json: String): Int = error("Unused page operation: restoreSettings")
        override fun settingsEpochForUi(): Long? = error("Unused page operation: settingsEpochForUi")
    }

    override val fingerprint = object : FingerprintSettings {
        override fun hideFingerprintAodEnabled() = effectiveAodFingerprint
        override fun hideFingerprintLockscreenEnabled() = false
        override fun hideFingerprintAppAuthEnabled() = false
    }

    override val powerButton = object : PowerButtonSettings {
        override fun powerButtonAction() = 0
        override fun setPowerButtonAction(action: Int) = Unit
    }

    override val backGesture = object : BackGestureSettings {
        override fun contextualSearchLongPress() = false
        override fun backGestureBoolean(key: String, defaultValue: Boolean): Boolean = error("Unused page operation: backGestureBoolean")
        override fun putBackGestureAppearance(values: Map<String, Boolean>): Unit = error("Unused page operation: putBackGestureAppearance")
    }

    override val camera = object : CameraAppSettings {
        override fun setCameraLegendaryMomentMode(mode: String): Unit = error("Unused page operation: setCameraLegendaryMomentMode")
        override fun cameraLegendaryMomentMode(): String = error("Unused page operation: cameraLegendaryMomentMode")
    }

    override val launcher = object : LauncherAppSettings {
        override fun openedFolderColumns(): Int = error("Unused page operation: openedFolderColumns")
        override fun allowAndroidWidgetsToAssistant(): Boolean = error("Unused page operation: allowAndroidWidgetsToAssistant")
        override fun androidWidgetStack(): Boolean = error("Unused page operation: androidWidgetStack")
        override fun folderStackPreview(): Boolean = error("Unused page operation: folderStackPreview")
        override fun hideRecentsClearButton(): Boolean = error("Unused page operation: hideRecentsClearButton")
        override fun recentsCardHaptic(): Boolean = error("Unused page operation: recentsCardHaptic")
        override fun xiaoaiGestureOptions(): Boolean = error("Unused page operation: xiaoaiGestureOptions")
    }

    override val systemUi = object : SystemUiAppSettings {
        override fun slotKey(slot: String): String = error("Unused page operation: slotKey")
        override fun getNotificationHeaderTimeScale(): Float = error("Unused page operation: getNotificationHeaderTimeScale")
        override fun iconStackedSignalStyle(): Int = error("Unused page operation: iconStackedSignalStyle")
        override fun notificationBadge(): Boolean = error("Unused page operation: notificationBadge")
        override fun notificationBlockFold(): Boolean = error("Unused page operation: notificationBlockFold")
        override fun notificationMoreSettings(): Boolean = error("Unused page operation: notificationMoreSettings")
    }

    override val platform = object : PlatformAppSettings {
        override fun nativePickerEnabled(key: String): Boolean = error("Unused page operation: nativePickerEnabled")
        override fun freeformBlurTransition(): Boolean = error("Unused page operation: freeformBlurTransition")
        override fun unlockAdaptiveRefreshPro(): Boolean = error("Unused page operation: unlockAdaptiveRefreshPro")
        override fun unlockMoreAonGestures(): Boolean = error("Unused page operation: unlockMoreAonGestures")
        override fun unlockMoreVisualPerception(): Boolean = error("Unused page operation: unlockMoreVisualPerception")
    }

    override val powerSave = object : PowerSaveSettings {
        override fun setPowerSaveFeature(key: String, enabled: Boolean): Unit = error("Unused page operation: setPowerSaveFeature")
        override fun batteryAutoPowerSaveThreshold(): Int = error("Unused page operation: batteryAutoPowerSaveThreshold")
        override fun batteryAutoPowerSaveEnabled(): Boolean = error("Unused page operation: batteryAutoPowerSaveEnabled")
        override fun exitPowerSaveWhenCharging(): Boolean = error("Unused page operation: exitPowerSaveWhenCharging")
        override fun keepHapticFeedbackInPowerSave(): Boolean = error("Unused page operation: keepHapticFeedbackInPowerSave")
        override fun keepRefreshRateInPowerSave(): Boolean = error("Unused page operation: keepRefreshRateInPowerSave")
        override fun keepSystemSoundsInPowerSave(): Boolean = error("Unused page operation: keepSystemSoundsInPowerSave")
        override fun keepWakeupGesturesInPowerSave(): Boolean = error("Unused page operation: keepWakeupGesturesInPowerSave")
    }

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
        store.backup.clearAllSettings()
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
