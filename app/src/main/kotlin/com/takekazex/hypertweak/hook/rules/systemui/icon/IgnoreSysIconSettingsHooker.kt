package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Ignores the system's own icon hiding, ported from Hyper Helper's `IgnoreSysIconSettings`.
 *
 * HyperOS blocks a few slots (`privacy`, and whatever `isIconBlocked` decides) and the network
 * speed visibility independently of the module; this hooks the blocking decisions:
 * `StatusBarIconView.isIconBlocked` is forced false except for the privacy slot (which follows
 * its own switch), `StatusBarIconObserver.loadStatusBarIcon` returns an empty slot name, and
 * `NetworkSpeedController.mShowNetworkSpeed` is forced to the net-speed slot mode.
 *
 * OS4 moved `isIconBlocked` off `StatusBarIconObserver` onto `StatusBarIconView`/`StatusIconDisplayable`
 * (see OS4_ADAPTATION_PLAN.md T3), so the hook target differs from upstream. Requires a SystemUI
 * restart.
 */
object IgnoreSysIconSettingsHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val ICON_VIEW_CLASS = "com.android.systemui.statusbar.StatusBarIconView"
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
        ignoreSysHide = Preferences.getBoolean(Preferences.KEY_ICON_IGNORE_SYS_HIDE, false)
        hidePrivacy = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_PRIVACY, false)
        // Show the net speed indicator unless its slot mode is "hidden everywhere" (4),
        // mirroring Hyper Helper (mode 0 = follow system still forces it shown when the
        // ignore-sys-hide feature is on).
        netSpeedShown = Preferences.getInt(Preferences.slotKey("network_speed"), 0) != 4

        if (!ignoreSysHide && !hidePrivacy) {
            DebugLog.hookSkipped(TAG, "IgnoreSysIconSettings", "disabled")
            return
        }

        // isIconBlocked moved to StatusBarIconView on OS4; the slot comes from mSlot.
        if (ignoreSysHide || hidePrivacy) {
            val iconViewClass = ICON_VIEW_CLASS.toClassOrNull()
            if (iconViewClass == null) {
                DebugLog.hookSkipped(TAG, ICON_VIEW_CLASS, "class not found")
            } else {
                val slotField = runCatching {
                    iconViewClass.getDeclaredField("mSlot").apply { isAccessible = true }
                }.getOrNull()
                iconViewClass.findMethodOrNull { name("isIconBlocked") }?.hook {
                    before { param ->
                        if (hidePrivacy) {
                            val slot = slotField?.get(param.thisObject) as? String
                            if (slot == "privacy") {
                                param.result = true
                                return@before
                            }
                        }
                        if (ignoreSysHide) {
                            param.result = false
                        }
                    }
                } ?: DebugLog.hookSkipped(TAG, "$ICON_VIEW_CLASS#isIconBlocked", "method not found")
            }
        }

        if (ignoreSysHide) {
            // Empty slot name so the observer never blocks a real slot.
            val observerClass = OBSERVER_CLASS.toClassOrNull()
            if (observerClass == null) {
                DebugLog.hookSkipped(TAG, OBSERVER_CLASS, "class not found")
            } else {
                observerClass.findMethodOrNull { name("loadStatusBarIcon") }?.hook {
                    before { param -> param.result = "" }
                } ?: DebugLog.hookSkipped(TAG, "$OBSERVER_CLASS#loadStatusBarIcon", "method not found")
            }

            // Keep the network speed indicator visible regardless of system state.
            val speedClass = SPEED_CLASS.toClassOrNull()
            if (speedClass == null) {
                DebugLog.hookSkipped(TAG, SPEED_CLASS, "class not found")
            } else {
                val showField = runCatching {
                    speedClass.getDeclaredField("mShowNetworkSpeed").apply { isAccessible = true }
                }.getOrNull()
                if (showField == null) {
                    DebugLog.hookSkipped(TAG, "$SPEED_CLASS#mShowNetworkSpeed", "field not found")
                } else {
                    speedClass.hookAllConstructors {
                        after { param ->
                            val controller = param.thisObject ?: return@after
                            runCatching { showField.setBoolean(controller, netSpeedShown) }
                                .onFailure { DebugLog.w(TAG, "mShowNetworkSpeed write failed", it) }
                        }
                    }
                    // R8 renames updateVisibility; match by the generated nest-method prefix.
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
            }
        }
    }
}
