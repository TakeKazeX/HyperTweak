package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * 禁止折叠通知 (SystemUI): stops notifications from being folded into the 「更多通知」 section.
 *
 * The ROM exposes a master gate for this — `MiuiBaseNotifUtil.shouldSuppressFold()`, i.e.
 * `IS_INTERNATIONAL_BUILD || MIUI_LITE_V2` — and it is tempting to force it to true. **Do not.**
 * Two rounds of device testing showed the gate is a "freeze", not a "hide":
 *
 * 1. Forcing it makes folding *worse*, not better. `FoldCoordinator$collectionListener$1.onEntryAdded`
 *    honours the gate with an early return, but `onEntryUpdated` does not check it at all, and
 *    `FoldCoordinator.scheduleHistoryNotification` is written as
 *    `if (!shouldSuppressFold() && …) { cancel; fold-now-or-return; }`. With the gate true the guarded
 *    branch is skipped and control reaches the *timeout* path below it, arming an `AlarmScheduler`
 *    alarm (`mHistoricalFoldingTimeout` = 6 h here) that later calls `NotificationUtil.setFold(true)`.
 *    So the gate turns "fold immediately, or not at all" into "fold everything after the timeout".
 * 2. Forcing it strands the fold **entrance**. The entrance is a real posted notification
 *    (`pkg=com.android.systemui tag=UNIMPORTANT id="UNIMPORTANT".hashCode()`, channel
 *    `id_aggregate` / `unimportant_entrance`) that lives in NMS and therefore survives SystemUI
 *    restarts. `FoldNotifControllerImpl.checkFoldEntrance` is the only code that cancels it, and that
 *    method *also* early-returns under the gate — so a stale entrance can never be cleaned up again.
 *    The result is exactly the reported symptom: 「更多通知」 that refuses to go away, opens with
 *    difficulty, and contains nothing (because folding is blocked).
 *
 * On a fresh boot with the gate true no entrance is ever created, which is why this only shows up as
 * a stuck leftover — but "cannot be undone" is not an acceptable property for a toggle.
 *
 * Blocking at the two fold *write* points instead is both sufficient and self-healing:
 *
 * - `FoldCoordinator.scheduleHistoryNotification` is the only method that arms a fold alarm, and the
 *   only place that folds immediately. Skipping it stops all scheduling and the alarm churn.
 * - `NotificationUtil.setFold` is the only writer of `ExpandedNotification.mIsFold` in the whole build
 *   (`NotificationUtil.java:280`), so forcing its flag false makes "no entry is ever folded" a
 *   single-point invariant covering every caller, including an alarm armed before the hook installed
 *   and the shade-collapse drain.
 *
 * With nothing ever marked folded, `FoldCoordinator.shouldShow()` stays false and the ROM's own
 * `checkFoldEntrance(false)` branch runs normally: it cancels the entrance notification and resets
 * `entranceShowing` / `lastEntrancePackageList`. That is why the fix removes the stale 「更多通知」
 * without any cleanup code of our own, and why it cannot come back.
 *
 * Deliberate trade-off: the long-press menu's 「收纳到更多通知」 item and the per-app fold rules stay
 * reachable (they are gated by the same ROM gate we no longer force). They write a rule that this
 * hooker then refuses to act on, so they are inert rather than broken. Blocking folding is what the
 * switch promises; hiding those affordances would mean forcing the gate again and re-introducing
 * both defects above.
 *
 * Independent of MIUI's per-app fold rules (`app_notification`'s `<pkg>_importance` = `1` / `-1` / `0`);
 * those values are left untouched. Every hook body stays minimal — `setFold` runs once per entry
 * change and `scheduleHistoryNotification` once per entry add/update.
 */
object NotificationBlockFoldHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "NotifBlockFold"
    private const val COORDINATOR =
        "com.android.systemui.statusbar.notification.collection.coordinator.FoldCoordinator"
    private const val NOTIFICATION_UTIL =
        "com.android.systemui.statusbar.notification.utils.NotificationUtil"

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
        // Independent installs: either hook alone already prevents folding (one by never scheduling
        // it, the other by refusing to record it), so a missing target must not disable the rest.
        val scheduler = hookFoldScheduler()
        val writer = hookFoldWriter()
        if (!scheduler && !writer) {
            DebugLog.hookSkipped(TAG, "notification fold suppression", "no fold path could be blocked")
        }
    }

    /**
     * Stops folding before it is scheduled: `scheduleHistoryNotification` is the only method that
     * folds immediately or arms a fold alarm, so skipping it removes both the timeout path and the
     * alarm churn.
     */
    private fun hookFoldScheduler(): Boolean {
        val coordinatorClass = COORDINATOR.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, COORDINATOR, "class not found")
            return false
        }
        val schedule = coordinatorClass.findMethodOrNull {
            name("scheduleHistoryNotification"); paramCount(1)
        } ?: run {
            DebugLog.hookSkipped(TAG, "$COORDINATOR#scheduleHistoryNotification", "method not found")
            return false
        }
        schedule.hook("notification_block_fold_schedule") {
            before { param ->
                if (!enabled) return@before
                HookFailurePolicy.open(TAG, "scheduleHistoryNotification", Unit) {
                    // void method: a null result skips the original body.
                    param.result = null
                }
            }
        }
        DebugLog.hookRegistered(TAG, "$COORDINATOR#scheduleHistoryNotification -> skip")
        return true
    }

    /**
     * Forces the fold flag off at its only writer, so no code path — including an alarm armed before
     * this hook installed — can mark an entry as folded.
     */
    private fun hookFoldWriter(): Boolean {
        val utilClass = NOTIFICATION_UTIL.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, NOTIFICATION_UTIL, "class not found")
            return false
        }
        val setFold = utilClass.findMethodOrNull {
            name("setFold"); paramCount(2)
        } ?: run {
            DebugLog.hookSkipped(TAG, "$NOTIFICATION_UTIL#setFold", "method not found")
            return false
        }
        // Small hot method: undo AOT inlining so the argument rewrite actually takes effect.
        deoptimize(setFold)
        setFold.hook("notification_block_fold_write") {
            before { param ->
                if (!enabled) return@before
                HookFailurePolicy.open(TAG, "setFold", Unit) {
                    // Keep the call (it also resets mIsFold/extras) but never let it fold.
                    param.args[1] = false
                }
            }
        }
        DebugLog.hookRegistered(TAG, "$NOTIFICATION_UTIL#setFold -> false")
        return true
    }
}
