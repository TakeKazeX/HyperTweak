package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
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
 * — the switch only writes the package key, and the channel key is absent. That is the classic
 * "开关打开了但锁屏还是不显示" mirage.
 *
 * This hook takes over that gate with per-channel semantics:
 *  - package-level 锁屏通知 OFF (`<pkg>_keyguard = false`)  → the whole app is hidden;
 *  - a real channel with an explicit key (`<pkg>_<channel>_keyguard`) → honored (true shows,
 *    false hides);
 *  - a channel with no key → default **show** (lifts the CN whitelist fallback, so third-party
 *    channels show by default instead of only the whitelisted ones).
 *
 * So with this switch on, every notification is allowed on the keyguard by default and a channel
 * only stays hidden when the user explicitly turned its 锁屏通知 off. The hook runs per-notification
 * and reads the SharedPreferences live, so toggling a channel takes effect without a restart.
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
    private const val PREFS_NAME = "app_notification"
    private const val PACKAGE_KEY_SUFFIX = "_keyguard"

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
                    val ctx = param.args.getOrNull(0) as? Context ?: return@open
                    val pkg = param.args.getOrNull(1) as? String ?: return@open
                    val channel = param.args.getOrNull(2) as? String
                    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                    // Package-level gate: an explicit <pkg>_keyguard=false hides the whole app.
                    val pkgKey = keyguardKey(pkg, null)
                    val pkgAllowed =
                        if (prefs.contains(pkgKey)) prefs.getBoolean(pkgKey, false) else true
                    if (!pkgAllowed) {
                        param.result = false
                        return@open
                    }
                    // Channel-level gate (only reached when a real, non-miscellaneous channel is
                    // supplied): an explicit <pkg>_<channel>_keyguard is honored, otherwise the CN
                    // whitelist fallback is bypassed so the channel shows by default.
                    if (channel.isNullOrEmpty()) {
                        param.result = pkgAllowed
                        return@open
                    }
                    val channelKey = keyguardKey(pkg, channel)
                    val channelAllowed =
                        if (prefs.contains(channelKey)) prefs.getBoolean(channelKey, false) else true
                    param.result = channelAllowed
                }
            }
        }
        DebugLog.d(TAG, "all notifications allowed on keyguard (per-channel aware)")
    }

    private fun keyguardKey(pkg: String, channel: String?): String {
        if (channel.isNullOrEmpty() || channel == "miscellaneous") {
            return "${pkg}$PACKAGE_KEY_SUFFIX"
        }
        return "${pkg}_${channel}$PACKAGE_KEY_SUFFIX"
    }
}
