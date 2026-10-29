package com.takekazex.hypertweak.app

import com.takekazex.hypertweak.hook.Preferences
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

/** Binds the app store to the sole runtime storage and migration owner. */
internal object PreferencesAppSettings : AppSettingsStore {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = Preferences.getBoolean(key, defaultValue)
    override fun getInt(key: String, defaultValue: Int): Int = Preferences.getInt(key, defaultValue)
    override fun getFloat(key: String, defaultValue: Float): Float = Preferences.getFloat(key, defaultValue)
    override fun putBoolean(key: String, value: Boolean): Unit = Preferences.putBoolean(key, value)
    override fun putInt(key: String, value: Int): Unit = Preferences.putInt(key, value)
    override fun putFloat(key: String, value: Float): Unit = Preferences.putFloat(key, value)
    override fun flush(): Unit = Preferences.flush()
    override fun getString(key: String, default: String): String = Preferences.getString(key, default)
    override fun getStringSet(key: String, default: Set<String>): Set<String> = Preferences.getStringSet(key, default)
    override fun putString(key: String, value: String): Unit = Preferences.putString(key, value)
    override fun putStringSet(key: String, value: Set<String>): Unit = Preferences.putStringSet(key, value)
    override fun contains(key: String): Boolean = Preferences.contains(key)
    override val backup = object : BackupSettings {
        override fun clearAllSettings(): Boolean = Preferences.clearAllSettings()
        override fun exportSettings(): String = Preferences.exportSettings()
        override fun restoreSettings(json: String): Int = Preferences.restoreSettings(json)
        override fun settingsEpochForUi(): Long? = Preferences.settingsEpochForUi()
    }

    override val fingerprint = object : FingerprintSettings {
        override fun hideFingerprintAodEnabled(): Boolean = Preferences.hideFingerprintAodEnabled()
        override fun hideFingerprintLockscreenEnabled(): Boolean = Preferences.hideFingerprintLockscreenEnabled()
        override fun hideFingerprintAppAuthEnabled(): Boolean = Preferences.hideFingerprintAppAuthEnabled()
    }

    override val powerButton = object : PowerButtonSettings {
        override fun powerButtonAction(): Int = Preferences.powerButtonAction()
        override fun setPowerButtonAction(action: Int): Unit = Preferences.setPowerButtonAction(action)
    }

    override val backGesture = object : BackGestureSettings {
        override fun contextualSearchLongPress(): Boolean = Preferences.contextualSearchLongPress()
        override fun backGestureBoolean(key: String, defaultValue: Boolean): Boolean = Preferences.backGestureBoolean(key, defaultValue)
        override fun putBackGestureAppearance(values: Map<String, Boolean>): Unit = Preferences.putBackGestureAppearance(values)
    }

    override val camera = object : CameraAppSettings {
        override fun setCameraLegendaryMomentMode(mode: String): Unit = Preferences.setCameraLegendaryMomentMode(mode)
        override fun cameraLegendaryMomentMode(): String = Preferences.cameraLegendaryMomentMode()
    }

    override val launcher = object : LauncherAppSettings {
        override fun desktopGrid(): Int = Preferences.desktopGrid()
        override fun commitDesktopGrid(value: Int): Boolean = Preferences.commitDesktopGrid(value)
        override fun desktopIconPercent(): Int = Preferences.desktopIconPercent()
        override fun commitDesktopIconPercent(value: Int): Boolean = Preferences.commitDesktopIconPercent(value)
        override fun openedFolderColumns(): Int = Preferences.openedFolderColumns()
        override fun allowAndroidWidgetsToAssistant(): Boolean = Preferences.allowAndroidWidgetsToAssistant()
        override fun androidWidgetStack(): Boolean = Preferences.androidWidgetStack()
        override fun folderStackPreview(): Boolean = Preferences.folderStackPreview()
        override fun hideRecentsClearButton(): Boolean = Preferences.hideRecentsClearButton()
        override fun recentsCardHaptic(): Boolean = Preferences.recentsCardHaptic()
        override fun xiaoaiGestureOptions(): Boolean = Preferences.xiaoaiGestureOptions()
    }

    override val systemUi = object : SystemUiAppSettings {
        override fun slotKey(slot: String): String = Preferences.slotKey(slot)
        override fun getNotificationHeaderTimeScale(): Float = Preferences.getNotificationHeaderTimeScale()
        override fun iconStackedSignalStyle(): Int = Preferences.iconStackedSignalStyle()
        override fun notificationBadge(): Boolean = Preferences.notificationBadge()
        override fun notificationBlockFold(): Boolean = Preferences.notificationBlockFold()
        override fun notificationMoreSettings(): Boolean = Preferences.notificationMoreSettings()
    }

    override val platform = object : PlatformAppSettings {
        override fun nativePickerEnabled(key: String): Boolean = Preferences.nativePickerEnabled(key)
        override fun freeformBlurTransition(): Boolean = Preferences.freeformBlurTransition()
        override fun unlockAdaptiveRefreshPro(): Boolean = Preferences.unlockAdaptiveRefreshPro()
        override fun unlockMoreAonGestures(): Boolean = Preferences.unlockMoreAonGestures()
        override fun unlockMoreVisualPerception(): Boolean = Preferences.unlockMoreVisualPerception()
    }

    override val powerSave = object : PowerSaveSettings {
        override fun setPowerSaveFeature(key: String, enabled: Boolean): Unit = Preferences.setPowerSaveFeature(key, enabled)
        override fun batteryAutoPowerSaveThreshold(): Int = Preferences.batteryAutoPowerSaveThreshold()
        override fun batteryAutoPowerSaveEnabled(): Boolean = Preferences.batteryAutoPowerSaveEnabled()
        override fun exitPowerSaveWhenCharging(): Boolean = Preferences.exitPowerSaveWhenCharging()
        override fun keepHapticFeedbackInPowerSave(): Boolean = Preferences.keepHapticFeedbackInPowerSave()
        override fun keepRefreshRateInPowerSave(): Boolean = Preferences.keepRefreshRateInPowerSave()
        override fun keepSystemSoundsInPowerSave(): Boolean = Preferences.keepSystemSoundsInPowerSave()
        override fun keepWakeupGesturesInPowerSave(): Boolean = Preferences.keepWakeupGesturesInPowerSave()
    }

}
