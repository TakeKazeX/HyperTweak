package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.rules.systemui.AODHooker
import com.takekazex.hypertweak.hook.rules.aicr.AicrDefaultBrowserHooker
import com.takekazex.hypertweak.hook.rules.systemui.HideFingerprintIcon
import com.takekazex.hypertweak.hook.rules.systemui.HideBottomBarHooker
import com.takekazex.hypertweak.hook.rules.systemui.HideLockscreenStatusBarHooker
import com.takekazex.hypertweak.hook.rules.systemui.KeyguardFingerprintAvoidHooker
import com.takekazex.hypertweak.hook.rules.systemui.MediaCardHideAppIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.MediaCardHideDeviceSwitchHooker
import com.takekazex.hypertweak.hook.rules.systemui.NotificationMiniWindowHooker
import com.takekazex.hypertweak.hook.rules.systemui.NotificationWhitelistBypassHooker
import com.takekazex.hypertweak.hook.rules.systemui.StatusBarHideSilentHooker
import com.takekazex.hypertweak.hook.rules.systemui.FreeformBlurTransitionHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.AppDetailsIconLaunchHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.SecurityCenterPopupHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.SecurityCenterDockHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.SecurityCenterDockHost
import com.takekazex.hypertweak.hook.rules.system.NativePhotoPickerHooker
import com.takekazex.hypertweak.hook.rules.system.NativeFilePickerHooker
import com.takekazex.hypertweak.hook.rules.system.VolumeKeyStepsHooker
import com.takekazex.hypertweak.hook.rules.system.BatteryAutoPowerSaveHooker
import com.takekazex.hypertweak.hook.rules.settings.HyperOsAiEntriesHooker
import com.takekazex.hypertweak.hook.rules.systemui.SystemUIPluginHooker
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenChargingDetailHooker
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenBottomTextHooker
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenDateHooker
import com.takekazex.hypertweak.hook.rules.systemui.glass.GlassMaterialHooker
import com.takekazex.hypertweak.hook.rules.intentresolver.SharePanelHooker
import com.takekazex.hypertweak.hook.rules.system.GestureBarTransitionHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.hook.rules.backgesture.AospBackSystemUiHooker
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.RuntimeSettingsPreferences
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.SettingsReloadDispatcher
import com.takekazex.hypertweak.settings.SettingsParticipantRegistry

/** Process-side effects. The app-side schema never constructs or initializes these hookers. */
internal object RuntimeSettingsParticipants {
    class Actions(
        val icons: (Set<String>) -> Boolean,
        val publishNative: () -> Unit,
        val aod: () -> Boolean,
        val header: (Set<String>) -> Unit,
        val notificationText: (Set<String>) -> Unit,
        val hookers: () -> List<BaseHooker>,
        val retired: () -> Boolean
    )

    fun install(dispatcher: SettingsReloadDispatcher, processName: String, isSystemServer: Boolean, actions: Actions) {
        val registry = SettingsParticipantRegistry(RuntimeSettingsGroups.forProcess(processName, isSystemServer))
        fun registerForProcess(id: String, apply: (Set<String>) -> Boolean) = registry.register(id, apply)
        registerForProcess(RuntimeSettingsGroups.ICONS) { keys ->
            actions.icons(keys)
        }
        registerForProcess(RuntimeSettingsGroups.NATIVE_PHOTO_PICKER) { _ ->
            NativePhotoPickerHooker.refreshSettings(); true
        }
        registerForProcess(RuntimeSettingsGroups.NATIVE_FILE_PICKER) { _ ->
            NativeFilePickerHooker.refreshSettings(); true
        }
        registerForProcess(RuntimeSettingsGroups.FORCE_SCREENSHOT) { _ ->
            com.takekazex.hypertweak.hook.rules.system.ForceScreenshotHooker.refreshSettings(); true
        }
        registerForProcess(RuntimeSettingsGroups.DARK_MODE) { _ ->
            com.takekazex.hypertweak.hook.rules.system.DarkModeScheduleHooker.refreshSettings()
        }
        registerForProcess(RuntimeSettingsGroups.SCREENSHOT) { _ -> Preferences.invalidateRuntimeReadCache(); true }
        registerForProcess(RuntimeSettingsGroups.BACK) { _ ->
            RuntimeSettingsPreferences.dispatch(setOf(RuntimeSettingsGroups.BACK))
            if (processName == "com.android.systemui") AospBackSystemUiHooker.refreshPreferences()
            true
        }
        registerForProcess(RuntimeSettingsGroups.NATIVE) { _ -> actions.publishNative(); true }
        registerForProcess(RuntimeSettingsGroups.DOCK) { _ -> com.takekazex.hypertweak.hook.rules.system.DockBackgroundHooker.refreshSettings() }
        registerForProcess(RuntimeSettingsGroups.VOLUME) { _ -> if (isSystemServer) VolumeKeyStepsHooker.refreshConfiguration(); true }
        // system_server: the hooker re-reads the attach switch per transition.
        registerForProcess(RuntimeSettingsGroups.GESTURE_BAR) { _ ->
            Preferences.invalidateRuntimeReadCache()
            GestureBarTransitionHooker.refreshSettings()
            true
        }
        // SystemUI: the draw hooks cache the switches, so refresh those copies. Disabling also
        // repaints the handle views, because suppressing onDraw leaves the last frame on screen.
        registerForProcess(RuntimeSettingsGroups.GESTURE_BAR_UI) { _ ->
            Preferences.invalidateRuntimeReadCache()
            HideBottomBarHooker.refreshSettings()
            true
        }
        registerForProcess(RuntimeSettingsGroups.XMSF) { _ -> Preferences.invalidateRuntimeReadCache(); true }
        // Re-evaluate presence only; cold targets use each feature's managed preparation.
        registerForProcess(RuntimeSettingsGroups.GATED_FEATURES) { _ ->
            Preferences.invalidateRuntimeReadCache()
            var applied = true
            actions.hookers().forEach { hooker ->
                runCatching {
                    hooker.refreshFeatureState()
                    if (hooker is AicrDefaultBrowserHooker) hooker.refreshPresentation()
                }.onFailure {
                    applied = false
                    DebugLog.w("HookEntry", "gated refresh failed hooker=${hooker.hookerName}", it)
                }
            }
            applied
        }
        registerForProcess(RuntimeSettingsGroups.APP_DETAILS) { _ ->
            if (processName == "com.miui.securitycenter") AppDetailsIconLaunchHooker.refreshSettings()
            true
        }
        registerForProcess(RuntimeSettingsGroups.SECURITY_POPUPS) { _ ->
            if (processName == SecurityCenterDockHost.PROCESS) SecurityCenterPopupHooker.refreshSettings() else true
        }
        registerForProcess(RuntimeSettingsGroups.SECURITY_DOCK) { _ ->
            if (processName != SecurityCenterDockHost.PROCESS) true
            else when (SecurityCenterDockHooker.refreshSettings()) {
                // Keep tracking the asynchronous owner until its main-thread repaint succeeds.
                SecurityCenterDockHooker.Refresh.PENDING -> false
                SecurityCenterDockHooker.Refresh.APPLIED -> true
                SecurityCenterDockHooker.Refresh.UNAVAILABLE -> false
            }
        }
        registerForProcess(RuntimeSettingsGroups.SECURITY_CHECKS) { _ -> Preferences.invalidateRuntimeReadCache(); true }
        registerForProcess(RuntimeSettingsGroups.OFFLINE_CONFIG) { _ -> Preferences.invalidateRuntimeReadCache(); true }
        registerForProcess(RuntimeSettingsGroups.XIAOAI_GESTURES) { _ -> true }
        registerForProcess(RuntimeSettingsGroups.SETTINGS_AI) { _ ->
            HyperOsAiEntriesHooker.refreshSettings()
        }
        registerForProcess(RuntimeSettingsGroups.SHARE_PANEL) { _ ->
            SharePanelHooker.refreshSettings()
            true
        }
        // Keyguard battery row: SystemUI's keyguard status bar (Duo) and the AOD plugin rows
        // (SystemUI-hosted plugin or the standalone AOD process).
        registerForProcess(RuntimeSettingsGroups.AOD) { _ -> actions.aod() }
        registerForProcess(RuntimeSettingsGroups.AOD_CAPABILITY) { _ ->
            AODHooker.refreshSettings()
            if (processName == "com.android.systemui") actions.aod()
            true
        }
        registerForProcess(RuntimeSettingsGroups.NOTIFICATION_ICONS) { _ ->
            StatusBarHideSilentHooker.refreshSettings()
            true
        }
        registerForProcess(RuntimeSettingsGroups.NOTIFICATION_MINI_WINDOW) { _ ->
            NotificationMiniWindowHooker.refreshSettings()
            true
        }
        registerForProcess(RuntimeSettingsGroups.NOTIFICATION_WHITELIST) { _ ->
            NotificationWhitelistBypassHooker.refreshSettings()
            true
        }
        registerForProcess(RuntimeSettingsGroups.FREEFORM_BLUR) { _ ->
            FreeformBlurTransitionHooker.refreshSettings()
            true
        }
        registerForProcess(RuntimeSettingsGroups.KEYGUARD) { _ ->
            Preferences.invalidateRuntimeReadCache()
            HideLockscreenStatusBarHooker.refreshSettings()
            LockscreenDateHooker.refreshSettings()
            HideFingerprintIcon.refreshSettings()
            true
        }
        registerForProcess(RuntimeSettingsGroups.HEADER) { keys ->
            actions.header(keys)
            true
        }
        registerForProcess(RuntimeSettingsGroups.NOTIFICATION_TEXT) { keys ->
            actions.notificationText(keys)
            true
        }
        registerForProcess(RuntimeSettingsGroups.MEDIA_CARD) { _ ->
            MediaCardHideAppIconHooker.refreshSettings()
            MediaCardHideDeviceSwitchHooker.refreshSettings()
            SystemUIPluginHooker.reloadControlCenterSettings()
            true
        }
        registerForProcess(RuntimeSettingsGroups.CONTROL_CENTER_PLUGIN) { _ ->
            SystemUIPluginHooker.reloadControlCenterSettings()
            true
        }
        registerForProcess(RuntimeSettingsGroups.GLASS) { _ ->
            GlassMaterialHooker.refreshSettings()
            true
        }
        registerForProcess(RuntimeSettingsGroups.LOCKSCREEN) { _ ->
            Preferences.invalidateRuntimeReadCache()
            LockscreenBottomTextHooker.refreshSettings()
            LockscreenChargingDetailHooker.refreshSettings()
            KeyguardFingerprintAvoidHooker.refreshSettings()
            true
        }
        registerForProcess(RuntimeSettingsGroups.POWER_SAVE) { _ ->
            BatteryAutoPowerSaveHooker.refreshSettings()
            true
        }
        registerForProcess(RuntimeSettingsGroups.ISLANDS) { _ ->
            if (actions.retired()) true else {
                Preferences.invalidateRuntimeReadCache()
                SystemUIPluginHooker.reloadIslandSettings()
            }
        }
        registry.install { id, apply ->
            if (id == RuntimeSettingsGroups.SECURITY_DOCK || id == RuntimeSettingsGroups.DARK_MODE)
                dispatcher.registerWithRetryBudget(id, 120, apply)
            else dispatcher.register(id, apply)
        }
    }
}
