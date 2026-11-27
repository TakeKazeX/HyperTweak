package com.takekazex.hypertweak.app

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSettingsPreferencePolicy
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
    private lateinit var applicationContext: android.content.Context
    fun bind(context: android.content.Context) { applicationContext = context.applicationContext ?: context }

    private fun <T> replaceDeviceSettings(replace: () -> T): T =
        com.takekazex.hypertweak.util.platform.CloudDnsSettings.replaceSettingsBlocking(applicationContext, this, replace)

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
        override fun clearAllSettings(): Boolean = runCatching {
            val analytics = DefaultAppServices.analytics
            if (analytics.canManage()) {
                check(!analytics.reconcile(applicationContext, request = false).failed) {
                    "Could not restore Analytics package states"
                }
            }
            replaceDeviceSettings { Preferences.clearAllSettings() }
        }.getOrElse {
            com.takekazex.hypertweak.util.logging.DebugLog.w("Settings", "device settings reset failed", it)
            false
        }
        override fun exportSettings(): String = Preferences.exportSettings()
        override fun restoreSettings(json: String): Int {
            com.takekazex.hypertweak.hook.preferences.SettingsBackupCodec.decode(json)
            return replaceDeviceSettings { Preferences.restoreSettings(json) }
        }
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
        override fun contextualSearchLongPress(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS, false)
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
        override fun migrateLegacyDockBackground(): Boolean = Preferences.migrateLegacyDockBackground()
        override fun desktopIconPercent(): Int = Preferences.desktopIconPercent()
        override fun commitDesktopIconPercent(value: Int): Boolean = Preferences.commitDesktopIconPercent(value)
        override fun openedFolderColumns(): Int = Preferences.openedFolderColumns()
        override fun allowAndroidWidgetsToAssistant(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT, false)
        override fun androidWidgetStack(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK, false)
        override fun folderStackPreview(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW, false)
        override fun hideRecentsClearButton(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON, false)
        override fun recentsCardHaptic(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC, false)
        override fun xiaoaiGestureOptions(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS, false)
    }

    override val systemUi = object : SystemUiAppSettings {
        override fun slotKey(slot: String): String = IconSettingsPreferencePolicy.slotKey(slot)
        override fun getNotificationHeaderTimeScale(): Float = Preferences.getFloat(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_TIME_SCALE, 1f)
        override fun iconStackedSignalStyle(): Int = Preferences.iconConfig().stacked.stackedSignalStyle
        override fun notificationBadge(): Boolean = Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_BADGE, false)
        override fun notificationBlockFold(): Boolean = Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_BLOCK_FOLD, false)
        override fun notificationMoreSettings(): Boolean = Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_MORE_SETTINGS, false)
    }

    override val platform = object : PlatformAppSettings {
        override fun nativePickerEnabled(key: String): Boolean = Preferences.nativePickerEnabled(key)
        override fun freeformBlurTransition(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_FREEFORM_BLUR_TRANSITION, false)
        override fun motionCuesFreeformFix(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_MOTION_CUES_FREEFORM_FIX, false)
        override fun unlockAdaptiveRefreshPro(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_UNLOCK_ADAPTIVE_REFRESH_PRO, false)
        override fun unlockMoreAonGestures(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_UNLOCK_MORE_AON_GESTURES, false)
        override fun unlockMoreVisualPerception(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_UNLOCK_MORE_VISUAL_PERCEPTION, false)
    }

    override val powerSave = object : PowerSaveSettings {
        override fun setPowerSaveFeature(key: String, enabled: Boolean): Unit = Preferences.setPowerSaveFeature(key, enabled)
        override fun batteryAutoPowerSaveThreshold(): Int = Preferences.batteryAutoPowerSaveThreshold()
        override fun batteryAutoPowerSaveEnabled(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_BATTERY_AUTO_POWER_SAVE_ENABLED, false)
        override fun exitPowerSaveWhenCharging(): Boolean = Preferences.getBoolean(PlatformSettingsKeys.KEY_EXIT_POWER_SAVE_WHEN_CHARGING, false)
        override fun keepHapticFeedbackInPowerSave(): Boolean = Preferences.keepHapticFeedbackInPowerSave()
        override fun keepRefreshRateInPowerSave(): Boolean = Preferences.keepRefreshRateInPowerSave()
        override fun keepSystemSoundsInPowerSave(): Boolean = Preferences.keepSystemSoundsInPowerSave()
        override fun keepWakeupGesturesInPowerSave(): Boolean = Preferences.keepWakeupGesturesInPowerSave()
    }

}
