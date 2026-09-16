package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Repairs the status-bar "silent notification" filter (隐藏状态栏中的静音通知) so the system's own
 * switch works again.
 *
 * HyperOS stubs `RankingCoordinatorInjectorImpl` out to an empty class, which degrades every
 * sectioner to a constant: `RankingCoordinator$1` (Alerting) always reports "in section" while the
 * Silent/Minimized sectioners always report "not in section". Every notification therefore lands in
 * Alerting(5), `silentSections` never matches, and the `isSilent` that
 * `ActiveNotificationsStoreBuilder.toModel` derives from the section is permanently false. The
 * downstream predicate `(showLowPriority || !m.isSilent)` is then always true, so the notification-
 * icon filter never removes anything even though the whole settings → NMS → listener → repository
 * chain is intact — the stock 「隐藏状态栏中的静音通知」 switch simply has no effect.
 *
 * Recomputing `isSilent` as `importance < IMPORTANCE_DEFAULT(3)` in `toModel`'s `after` makes that
 * predicate literally equivalent to AOSP's `(showLowPriority || importance >= 3)`, which restores
 * the stock switch.
 *
 * This is a code repair, not a behavior switch: it has no preference of its own and rides on
 * [Preferences.KEY_NOTIFICATION_MORE_SETTINGS], and it deliberately leaves `hideSilentStatusIcons`
 * alone. Whether silent icons are hidden stays whatever the user set in the stock notification
 * settings (隐藏功能 → 通知设置), whose switch this repair makes honest.
 *
 * Blast radius (verified against the device build): `isSilent` has exactly one functional consumer,
 * the status-bar icon filter. AOD goes through a different flow whose `showLowPriority` is
 * hard-coded true, and the shade's grouping uses the section mechanism rather than this model, so
 * neither is affected.
 */
object StatusBarHideSilentHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "HideSilent"
    private const val BUILDER =
        "com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsStoreBuilder"
    private const val IMPORTANCE_DEFAULT = 3

    @Volatile
    private var enabled = false

    private var rankingField: Field? = null
    private var getImportance: Method? = null
    private var isSilentField: Field? = null

    override fun onPrepareHotReload() {
        enabled = false
        rankingField = null
        getImportance = null
        isSilentField = null
    }

    override fun onHook() {
        enabled = Preferences.notificationMoreSettings()
        if (!enabled) {
            DebugLog.hookSkippedDebug(TAG, "status bar silent filter", "disabled")
            return
        }
        val builderClass = BUILDER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BUILDER, "class not found")
            return
        }
        // toModel has two single-argument overloads (GroupEntry and NotificationEntry); matching on
        // the parameter count alone can pick the group one, whose return type has no isSilent field.
        val toModel = builderClass.declaredMethods.firstOrNull {
            it.name == "toModel" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name.endsWith("NotificationEntry")
        }?.apply { isAccessible = true } ?: run {
            DebugLog.hookSkipped(TAG, "$BUILDER#toModel(NotificationEntry)", "method not found")
            return
        }
        if (!resolveReflection(toModel)) {
            DebugLog.hookSkipped(TAG, "$BUILDER#toModel isSilent wiring", "resolve failed")
            return
        }

        toModel.hook("notification_more_settings_silent_filter") {
            after { param ->
                if (!enabled) return@after
                HookFailurePolicy.open(TAG, "toModel.isSilent", Unit) {
                    val entry = param.args.getOrNull(0) ?: return@open
                    val model = param.result ?: return@open
                    // mRanking is null for synthetic/placeholder entries. Keep the host's own value
                    // instead of treating it as importance 0, which would hide the icon.
                    val ranking = rankingField?.get(entry) ?: return@open
                    val importance = getImportance?.invoke(ranking) as? Int ?: return@open
                    isSilentField?.setBoolean(model, importance < IMPORTANCE_DEFAULT)
                }
            }
        }
        DebugLog.hookRegistered(TAG, "$BUILDER#toModel isSilent = importance < $IMPORTANCE_DEFAULT")
    }

    /** Caches the reflective members used on the hot path; false when any of them is missing. */
    private fun resolveReflection(toModel: Method): Boolean {
        rankingField = runCatching { toModel.parameterTypes[0].getField("mRanking") }.getOrNull()
        getImportance = rankingField?.let { field ->
            runCatching { field.type.getMethod("getImportance") }.getOrNull()
        }
        isSilentField = runCatching { toModel.returnType.getField("isSilent") }.getOrNull()
        return rankingField != null && getImportance != null && isSilentField != null
    }
}
