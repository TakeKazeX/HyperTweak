package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Keeps notifications on the lockscreen after they were already seen (解锁后保留通知), OS4
 * SystemUI.
 *
 * MIUI's `NotificationFilterController.forceHideOnKeyguard(NotificationEntry)` aggregates two
 * independent gates: the per-app `canShowOnKeyguard()` check (Feature 3's domain, handled by
 * `LockscreenAllNotificationsHooker`) and the `mHasShownAfterUnlock` flag — two writer points
 * (`KeyguardCoordinator$attach$1` and `OriginalUnseenKeyguardCoordinator$collectionListener$1`)
 * mark every clearable/group-summary notification as "already shown" once the keyguard has been
 * left, so the next lock hides it again.
 *
 * Forcing the whole method to return false would also relax Feature 3, so this hook keeps the
 * `canShowOnKeyguard()` gate and only drops the `mHasShownAfterUnlock` branch, mirroring the
 * report's recommended decoupling:
 *
 * ```
 * if (!entry.mSbn.canShowOnKeyguard()) return true;
 * return false;
 * ```
 *
 * `forceHideOnKeyguard` is static; `NotificationEntry.mSbn` (public) and
 * `ExpandedNotification.canShowOnKeyguard()` (public final) are resolved by name at install
 * time. The master switch gates hook installation and needs a SystemUI restart.
 */
object LockscreenKeepNotificationsHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "LockscreenKeepNotifications"
    private const val FILTER_CONTROLLER =
        "com.android.systemui.statusbar.notification.policy.NotificationFilterController"
    private const val ENTRY_CLASS =
        "com.android.systemui.statusbar.notification.collection.NotificationEntry"

    @Volatile
    private var mSbnField: Field? = null

    @Volatile
    private var canShowOnKeyguard: Method? = null

    override fun onHook() {
        mSbnField = null
        canShowOnKeyguard = null
        if (!Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, false)) {
            DebugLog.hookSkipped(TAG, "lockscreen keep notifications", "disabled")
            return
        }
        val entryCls = ENTRY_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, ENTRY_CLASS, "class not found")
            return
        }
        mSbnField = findField(entryCls, "mSbn")
        if (mSbnField == null) {
            DebugLog.hookSkipped(TAG, "$ENTRY_CLASS#mSbn", "field not found")
            return
        }
        val sbnType = mSbnField?.type ?: return
        canShowOnKeyguard = sbnType.methods.firstOrNull { it.name == "canShowOnKeyguard" } ?: run {
            DebugLog.hookSkipped(TAG, "${sbnType.name}#canShowOnKeyguard", "method not found")
            return
        }
        val controller = FILTER_CONTROLLER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, FILTER_CONTROLLER, "class not found")
            return
        }
        val forceHide = controller.declaredMethods.firstOrNull {
            it.name == "forceHideOnKeyguard" && it.parameterTypes.size == 1
        } ?: run {
            DebugLog.hookSkipped(TAG, "$FILTER_CONTROLLER#forceHideOnKeyguard", "method not found")
            return
        }
        forceHide.hook {
            before { param ->
                HookFailurePolicy.open(TAG, "forceHideOnKeyguard", Unit) {
                    val entry = param.args.getOrNull(0) ?: return@open
                    val sbn = mSbnField?.get(entry) ?: return@open
                    val canShow = canShowOnKeyguard?.invoke(sbn) as? Boolean ?: return@open
                    // Keep the canShowOnKeyguard gate, drop the mHasShownAfterUnlock branch.
                    param.result = !canShow
                }
            }
        }
        DebugLog.d(TAG, "lockscreen keeps already-seen notifications")
    }

    override fun onPrepareHotReload() {
        mSbnField = null
        canShowOnKeyguard = null
    }

    private fun findField(cls: Class<*>, name: String): Field? {
        var type: Class<*>? = cls
        while (type != null && type != Any::class.java) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        return null
    }
}
