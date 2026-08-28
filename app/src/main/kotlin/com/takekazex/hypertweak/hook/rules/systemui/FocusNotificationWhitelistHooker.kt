package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method

/**
 * Removes the focus-notification whitelist (移除焦点通知白名单), OS4 SystemUI.
 *
 * The per-app focus capability gate is `NotificationSettingsManager.canShowFocusState(Context,
 * pkg)` / `canShowFocusStateApp(Context, pkg)`: cloud lists (`systemui_support_block_focus_list`,
 * `systemui_not_support_block_focus_list`, `systemui_support_block_signatures_focus_list`, delivered
 * through `MiuiSettings.SettingsCloudData`) decide whether a package may show 焦点通知 (the
 * Dynamic-Island-style focus bubble) — 1 = allowed, -1 = not allowed, otherwise the user's
 * per-app `<pkg>_focus` preference. Consumers on this build: the `NotificationProvider` /
 * `NotificationProviderPublic` content-provider queries and the shade-menu 焦点通知 toggle
 * (`MiuiNotificationMenuRow`, shown only when `canShowFocusStateApp(...) == 1`; tapping it writes
 * `_focus = 0` via `setShowFocus`). The render path itself keys off the notification's
 * `miui.focus.*` extras (`mIsFocusNotification`), so lifting the gate here exposes the focus
 * capability to arbitrary apps.
 *
 * The before-hook forces `result = 1` for every package **except** those the user explicitly
 * disabled: the shade-menu 焦点通知 off toggle persists `<pkg>_focus = 0` in the
 * `app_notification` SharedPreferences, and that value is read directly (via the method's Context
 * argument — the same file `getFocusState` reads). A package with `_focus == 0` is left to the
 * original logic (returns 0, or -1 if it is also cloud-blacklisted), so the user can always turn
 * an app off without losing the unlock for everything else. Read at hook-install time; requires a
 * SystemUI restart.
 */
object FocusNotificationWhitelistHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "FocusNotificationWhitelist"
    private const val SETTINGS_MANAGER =
        "com.miui.systemui.notification.NotificationSettingsManager"
    private const val NOTIF_PREFS = "app_notification"

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, false)) {
            DebugLog.hookSkipped(TAG, "focus notification whitelist", "disabled")
            return
        }
        val manager = SETTINGS_MANAGER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, SETTINGS_MANAGER, "class not found")
            return
        }
        val canShow = manager.declaredMethods.firstOrNull {
            it.name == "canShowFocusState" && it.parameterTypes.size == 2
        } ?: run {
            DebugLog.hookSkipped(TAG, "$SETTINGS_MANAGER#canShowFocusState", "method not found")
            return
        }
        val canShowApp = manager.declaredMethods.firstOrNull {
            it.name == "canShowFocusStateApp" && it.parameterTypes.size == 2
        } ?: run {
            DebugLog.hookSkipped(TAG, "$SETTINGS_MANAGER#canShowFocusStateApp", "method not found")
            return
        }

        installUnlock(canShow, "$SETTINGS_MANAGER#canShowFocusState")
        installUnlock(canShowApp, "$SETTINGS_MANAGER#canShowFocusStateApp")
        DebugLog.d(TAG, "focus notification whitelist removed (user-disabled apps kept off)")
    }

    private fun installUnlock(method: Method, target: String) {
        method.hook {
            before { param ->
                HookFailurePolicy.open(TAG, target, Unit) {
                    val context = param.args[0] as? Context ?: return@open
                    val pkg = param.args[1] as? String ?: return@open
                    val focusPref = try {
                        context.getSharedPreferences(NOTIF_PREFS, Context.MODE_PRIVATE)
                            .getInt("${pkg}_focus", -1)
                    } catch (t: Throwable) {
                        // Legacy Boolean storage or an unreadable value: treat as not disabled.
                        -1
                    }
                    if (focusPref != 0) {
                        param.result = 1
                    }
                }
            }
        }
    }
}
