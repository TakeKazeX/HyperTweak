package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Adjusts the integer emitted by NotificationIconObserver's existing max-icon combine transform.
 * The concrete `maxIconFlow` is deliberately left untouched because AOD and status-bar binders
 * consume that concrete flow type.
 *
 * Bounds come from [NotificationIconLimit], which the settings row also uses.
 */
object NotificationMaxNumberHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val TRANSFORM_CLASS =
        "com.android.systemui.statusbar.policy.NotificationIconObserver\$maxIconFlow\$1"

    @Volatile private var enabled = false
    @Volatile private var maximum = NotificationIconLimit.DEFAULT

    override fun onPrepareHotReload() {
        enabled = false
        maximum = NotificationIconLimit.DEFAULT
    }

    override fun onHook() {
        enabled = Preferences.getBoolean(Preferences.KEY_STATUSBAR_NOTIFICATION_MAX, false)
        maximum = NotificationIconLimit.clamp(
            Preferences.getInt(
                Preferences.KEY_STATUSBAR_NOTIFICATION_ICON_MAX,
                NotificationIconLimit.DEFAULT
            )
        )
        if (!enabled) {
            DebugLog.hookSkippedDebug(TAG, "NotificationMaxNumber", "disabled")
            return
        }
        val transformClass = TRANSFORM_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, TRANSFORM_CLASS, "class not found")
            return
        }
        val invoke = transformClass.findMethodOrNull { name("invoke"); paramCount(3) } ?: run {
            DebugLog.hookSkipped(TAG, "$TRANSFORM_CLASS#invoke", "method not found")
            return
        }
        deoptimize(invoke)
        invoke.hook {
            after { param ->
                if (enabled && param.result is Number) param.result = maximum
            }
        }
        DebugLog.hookRegistered(TAG, "NotificationIconObserver maxIconFlow transform=$maximum")
    }
}
