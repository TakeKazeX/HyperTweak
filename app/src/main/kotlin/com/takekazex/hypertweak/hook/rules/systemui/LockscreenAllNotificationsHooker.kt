package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Lets every notification show on the lockscreen (锁屏通知限制 / 让所有通知都能在锁屏显示), OS4
 * SystemUI.
 *
 * The authoritative per-app gate is `NotificationSettingsManager.canShowOnKeyguard(Context,
 * pkg, channel)` (`:512`): on CN builds (`USE_WHITE_LISTS = !IS_INTERNATIONAL_BUILD`) the
 * channel-level fallback is a whitelist check (`mAllowKeyguardPackages.contains(pkg)`), so most
 * third-party apps' real channels never pass even when the user toggled the app-level switch on
 * — the switch only writes the package key, and the channel key is absent. Forcing the method to
 * return true lifts both the package level and the channel level, so every notification gets
 * `ExpandedNotification.mCanShowOnKeyguard = true` (via `MiuiBaseNotifUtil` →
 * `NotificationListener` → `NotificationEntry`) and `canShowOnKeyguard()` passes.
 *
 * `forceHideOnKeyguard` re-checks `entry.mSbn.canShowOnKeyguard()` as its own gate, so this hook
 * also benefits that path — but it does NOT touch `mHasShownAfterUnlock` (that is the separate
 * Feature 4, `LockscreenKeepNotificationsHooker`).
 *
 * The master switch gates hook installation and needs a SystemUI restart.
 */
object LockscreenAllNotificationsHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "LockscreenAllNotifications"
    private const val SETTINGS_MANAGER =
        "com.miui.systemui.notification.NotificationSettingsManager"

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, false)) {
            DebugLog.hookSkipped(TAG, "lockscreen notification gate", "disabled")
            return
        }
        val manager = SETTINGS_MANAGER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, SETTINGS_MANAGER, "class not found")
            return
        }
        val canShow = manager.declaredMethods.firstOrNull {
            it.name == "canShowOnKeyguard" && it.parameterTypes.size == 3
        } ?: run {
            DebugLog.hookSkipped(TAG, "$SETTINGS_MANAGER#canShowOnKeyguard", "method not found")
            return
        }
        canShow.hook {
            before { param ->
                HookFailurePolicy.open(TAG, "canShowOnKeyguard", Unit) {
                    param.result = true
                }
            }
        }
        DebugLog.d(TAG, "all notifications allowed on keyguard")
    }
}
