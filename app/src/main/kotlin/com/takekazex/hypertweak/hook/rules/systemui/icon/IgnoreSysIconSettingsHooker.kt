package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Ignores Xiaomi's status-bar hide-list source without overriding IconManager's own block result.
 *
 * On OS4 `IconManager.refreshIconGroup()` combines the observer flow, the tuner list, and each
 * manager's block list. Forcing `StatusBarIconView.isIconBlocked()` to false therefore defeats the
 * slot policy and left placement. The privacy option intentionally stays in the observer list:
 * `MiuiHomePrivacyController` consumes that `privacy` item to select its normal show-dot branch.
 */
object IgnoreSysIconSettingsHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val OBSERVER_CLASS = "com.android.systemui.statusbar.policy.StatusBarIconObserver"
    private const val SPEED_CLASS = "com.android.systemui.statusbar.policy.NetworkSpeedController"

    @Volatile
    private var ignoreSysHide = false

    @Volatile
    private var hidePrivacy = false

    @Volatile
    private var netSpeedShown = false

    override fun onPrepareHotReload() {
        ignoreSysHide = false
        hidePrivacy = false
        netSpeedShown = false
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        val snapshot = IconTunerOptions.snapshot()
        ignoreSysHide = snapshot.ignoreSystemHide
        hidePrivacy = snapshot.hidePrivacy
        netSpeedShown = IconSlotMode.from(
            snapshot.policy.slotModes["network_speed"] ?: 0
        ) != IconSlotMode.HIDE_EVERYWHERE

        if (!ignoreSysHide && !hidePrivacy) {
            DebugLog.hookSkipped(TAG, "IgnoreSysIconSettings", "disabled")
            return
        }

        hookStatusBarIconObserver()
        if (ignoreSysHide) hookNetworkSpeed()
    }

    private fun hookStatusBarIconObserver() {
        val observerClass = OBSERVER_CLASS.toClassOrNull()
        if (observerClass == null) {
            DebugLog.hookSkipped(TAG, OBSERVER_CLASS, "class not found")
            return
        }
        observerClass.findMethodOrNull {
            name("loadStatusBarIcon")
            noParams()
        }?.hook {
            after { param ->
                val original = param.result as? String ?: return@after
                val slots = if (ignoreSysHide) {
                    LinkedHashSet<String>()
                } else {
                    parseSlots(original)
                }
                if (hidePrivacy) slots += "privacy"
                param.result = slots.joinToString(",")
            }
        } ?: DebugLog.hookSkipped(TAG, "$OBSERVER_CLASS#loadStatusBarIcon", "method not found")
    }

    private fun hookNetworkSpeed() {
        val speedClass = SPEED_CLASS.toClassOrNull()
        if (speedClass == null) {
            DebugLog.hookSkipped(TAG, SPEED_CLASS, "class not found")
            return
        }
        val showField = runCatching {
            speedClass.getDeclaredField("mShowNetworkSpeed").apply { isAccessible = true }
        }.getOrNull()
        if (showField == null) {
            DebugLog.hookSkipped(TAG, "$SPEED_CLASS#mShowNetworkSpeed", "field not found")
            return
        }
        speedClass.hookAllConstructors {
            after { param ->
                runCatching { showField.setBoolean(param.thisObject, netSpeedShown) }
                    .onFailure { DebugLog.w(TAG, "mShowNetworkSpeed write failed", it) }
            }
        }
        // R8 keeps this generated nest-method prefix on the OS4 build. Keep the hook limited to
        // the show callback so normal hide requests and the user's slot mode remain intact.
        speedClass.declaredMethods.firstOrNull { it.name.contains("mupdateVisibility") }?.hook {
            before { param ->
                val reason = param.args.getOrNull(1) as? String
                if (reason == "show" && netSpeedShown) {
                    val controller = param.args.getOrNull(0) ?: return@before
                    runCatching { showField.setBoolean(controller, true) }
                        .onFailure { DebugLog.w(TAG, "mShowNetworkSpeed write failed", it) }
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$SPEED_CLASS nest mupdateVisibility", "method not found")
    }

    private fun parseSlots(value: String): LinkedHashSet<String> = value
        .split(',')
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toCollection(LinkedHashSet())
}
