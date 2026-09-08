package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Adjusts the integer emitted by NotificationIconObserver's existing max-icon combine transform.
 * The concrete `maxIconFlow` is deliberately left untouched because AOD and status-bar binders
 * consume that concrete flow type.
 */
object NotificationMaxNumberHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val TRANSFORM_CLASS =
        "com.android.systemui.statusbar.policy.NotificationIconObserver\$maxIconFlow\$1"

    @Volatile private var enabled = false
    @Volatile private var maximum = 3

    override fun onPrepareHotReload() {
        enabled = false
        maximum = 3
    }

    override fun onHook() {
        enabled = Preferences.getBoolean(Preferences.KEY_STATUSBAR_NOTIFICATION_MAX, false)
        maximum = Preferences.getInt(
            Preferences.KEY_STATUSBAR_NOTIFICATION_ICON_MAX,
            3
        ).coerceIn(1, 20)
        if (!enabled) {
            DebugLog.hookSkipped(TAG, "NotificationMaxNumber", "disabled")
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
