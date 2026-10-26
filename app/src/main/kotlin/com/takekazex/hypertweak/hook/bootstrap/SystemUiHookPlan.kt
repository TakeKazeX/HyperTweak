package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.rules.backgesture.AospBackSystemUiHooker
import com.takekazex.hypertweak.hook.rules.module.RestartBroadcastHooker
import com.takekazex.hypertweak.hook.rules.system.BatteryAutoPowerSaveHooker
import com.takekazex.hypertweak.hook.rules.systemui.AODHooker
import com.takekazex.hypertweak.hook.rules.systemui.AodWallpaperHandoffHooker
import com.takekazex.hypertweak.hook.rules.systemui.AospSystemUiPluginBlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.AospVolumeExtrasHooker
import com.takekazex.hypertweak.hook.rules.systemui.AospVolumeHapticHooker
import com.takekazex.hypertweak.hook.rules.systemui.ExtendUnlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.FocusNotificationWhitelistHooker
import com.takekazex.hypertweak.hook.rules.systemui.FreeformBlurTransitionHooker
import com.takekazex.hypertweak.hook.rules.systemui.gesturebar.HideBottomBarHooker
import com.takekazex.hypertweak.hook.rules.systemui.lockscreen.HideFingerprintIcon
import com.takekazex.hypertweak.hook.rules.systemui.lockscreen.HideLockscreenStatusBarHooker
import com.takekazex.hypertweak.hook.rules.systemui.ImmediateMonetRefreshHooker
import com.takekazex.hypertweak.hook.rules.systemui.lockscreen.KeyguardFingerprintAvoidHooker
import com.takekazex.hypertweak.hook.rules.systemui.lockscreen.LockscreenAllNotificationsHooker
import com.takekazex.hypertweak.hook.rules.systemui.lockscreen.LockscreenBottomTextHooker
import com.takekazex.hypertweak.hook.rules.systemui.lockscreen.LockscreenChargingDetailHooker
import com.takekazex.hypertweak.hook.rules.systemui.lockscreen.LockscreenDateHooker
import com.takekazex.hypertweak.hook.rules.systemui.lockscreen.LockscreenKeepNotificationsHooker
import com.takekazex.hypertweak.hook.rules.systemui.MediaCardHideAppIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.MediaCardHideDeviceSwitchHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationAbsoluteTimeHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationBlockFoldHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationFontWeightHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationHeaderClockSecondsHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationHeaderHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationHeaderWeatherHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationMiniWindowHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationMonetTextColorHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationWhitelistBypassHooker
import com.takekazex.hypertweak.hook.rules.systemui.ProxyLaunchHooker
import com.takekazex.hypertweak.hook.rules.systemui.StatusBarHideSilentHooker
import com.takekazex.hypertweak.hook.rules.systemui.SystemUIPluginHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.SystemUiBubbleNotificationWhitelistHooker
import com.takekazex.hypertweak.hook.rules.systemui.SystemUiScreenshotSoundHooker
import com.takekazex.hypertweak.hook.rules.systemui.UnlockClipboardHooker
import com.takekazex.hypertweak.hook.rules.systemui.glass.GlassMaterialHooker

/** Host-owned installation order; factories run only when the caller installs the plan. */
internal object SystemUiHookPlan {
    fun steps(packageName: String, processName: String): List<HookStep> = buildList {
        when (packageName) {
            "com.android.systemui" -> {
                hook { RestartBroadcastHooker }
                hook { com.takekazex.hypertweak.hook.base.ViewVisibilityRouter }
                hook { AODHooker }
                hook { AodWallpaperHandoffHooker }
                hook { HideFingerprintIcon }
                hook { HideLockscreenStatusBarHooker }
                hook { NotificationHeaderClockSecondsHooker }
                hook { NotificationHeaderHooker }
                hook { NotificationHeaderWeatherHooker }
                hook { NotificationMonetTextColorHooker }
                hook { NotificationFontWeightHooker }
                hook { NotificationAbsoluteTimeHooker }
                hook { SystemUiScreenshotSoundHooker }
                hook { KeyguardFingerprintAvoidHooker }
                hook { LockscreenChargingDetailHooker }
                hook { LockscreenBottomTextHooker }
                hook { LockscreenDateHooker }
                hook { ImmediateMonetRefreshHooker }
                // Auto power save needs a persistent process: SystemUI holds DEVICE_POWER and is
                // always scoped, so the battery receiver lives here rather than in the app.
                hook { BatteryAutoPowerSaveHooker }
                hook { SystemUIPluginHooker }
                hook { AospSystemUiPluginBlockHooker }
                hook { AospVolumeHapticHooker }
                hook { AospBackSystemUiHooker }
                hook { AospVolumeExtrasHooker }
                hook { ExtendUnlockHooker }
                hook { ProxyLaunchHooker }
                hook { UnlockClipboardHooker }
                hook { HideBottomBarHooker }
                add(HookStep.Icons)
                hook { GlassMaterialHooker }
                hook { MediaCardHideAppIconHooker }
                hook { MediaCardHideDeviceSwitchHooker }
                hook { LockscreenAllNotificationsHooker }
                hook { LockscreenKeepNotificationsHooker }
                hook { FocusNotificationWhitelistHooker }
                hook { NotificationMiniWindowHooker }
                hook { NotificationWhitelistBypassHooker }
                hook { SystemUiBubbleNotificationWhitelistHooker }
                // Notification fold suppression (its own switch) and the status-bar "silent
                // notification" classifier. Its explicit two-mode icon filter is delivered live;
                // the channel-settings repair still belongs to the Settings process.
                hook { NotificationBlockFoldHooker }
                hook { StatusBarHideSilentHooker }
                // 多任务过渡模糊: the WM Shell multitasking classes reach this process through the
                // `com.miui.wm.shell` shared library declared in SystemUI's manifest, so the plain
                // SystemUI class loader resolves them (same channel as HideBottomBarHooker's
                // MiuiDecorationBottomView).
                hook { FreeformBlurTransitionHooker }
                // ControlCenterCardsEditHooker and ControlCenterCardResizeHooker attach only
                // through SystemUIPluginHooker: their target classes live in
                // the miui.systemui.plugin APK, whose PathClassLoader only exists after
                // PluginInstance.loadPlugin() runs — SystemUIPluginHooker attaches it with that
                // loader (see attachPluginHooker).
            }
        }
    }
}
