package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * 禁止折叠通知 (SystemUI): stops notifications from being folded into 「历史通知」.
 *
 * HyperOS ships the master switch for this already —
 *
 * ```java
 * public static boolean shouldSuppressFold() {
 *     return MiuiConfigs.IS_INTERNATIONAL_BUILD || MiuiConfigs.MIUI_LITE_V2;
 * }
 * ```
 *
 * — and consults it from roughly 35 call sites that cover the whole fold subsystem: the 16
 * `InnerNotifBean.mShouldCustomFold` computations in `MiuiBaseNotifUtil`, `FoldCoordinator`'s
 * immediate-vs-timeout branch and its `onEntryAdded` early return, the per-app fold-rule dispatch,
 * the long-press notification menu entry, and the fold history/entry UI. Forcing it to true
 * therefore switches the CN behavior to the international build's "never fold" in exactly the way
 * the ROM itself designed, with one hook instead of many.
 *
 * This is unrelated to MIUI's per-app fold *rules* (`<pkg>_importance` in the `app_notification`
 * preferences, `1` / `-1` / `0`); those values are left alone.
 *
 * The hook body stays minimal on purpose: the method is called ~35 times per notification batch, so
 * it only writes `param.result` and does no resolution or I/O.
 */
object NotificationBlockFoldHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "NotifBlockFold"
    private const val UTIL = "com.miui.systemui.notification.MiuiBaseNotifUtil"

    @Volatile
    private var enabled = false

    override fun onPrepareHotReload() {
        enabled = false
    }

    override fun onHook() {
        enabled = Preferences.notificationBlockFold()
        if (!enabled) {
            DebugLog.hookSkipped(TAG, "block notification fold", "disabled")
            return
        }
        val utilClass = UTIL.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, UTIL, "class not found")
            return
        }
        val shouldSuppress = utilClass.findMethodOrNull {
            name("shouldSuppressFold"); noParams()
        } ?: run {
            DebugLog.hookSkipped(TAG, "$UTIL#shouldSuppressFold", "method not found")
            return
        }
        shouldSuppress.hook("notification_block_fold") {
            before { param ->
                if (!enabled) return@before
                HookFailurePolicy.open(TAG, "shouldSuppressFold", Unit) {
                    param.result = true
                }
            }
        }
        DebugLog.hookRegistered(TAG, "$UTIL#shouldSuppressFold -> true")
    }
}
