package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Status-bar WiFi icon visibility, ported from Hyper Helper's `WifiIcon` hooker
 * (see cache/xiaomihelper-2bfd4873a4138764, OS4 comparison in OS4_ADAPTATION_PLAN.md).
 *
 * `WifiIcon$Companion.fromModel` builds the displayed icon model; the hook substitutes the
 * `Hidden` instance for a connected (`Active`) model so the icon never shows. The
 * `WifiViewModel` activity/standard getters return a shared `0` flow. The OS4 factory assigns the
 * backing fields after construction, so getter hooks are the stable read boundary. Requires a
 * SystemUI restart.
 */
object WifiIconHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val WIFI_ICON_CLASS = "com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon"
    private const val ACTIVE_CLASS = "com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel\$Active"
    private const val HIDDEN_CLASS = "com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon\$Hidden"
    private const val VM_CLASS = "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel"

    @Volatile
    private var hideActivity = false
    @Volatile
    private var hideType = false
    @Volatile
    private var hideUnavailable = false

    override fun onPrepareHotReload() {
        hideActivity = false
        hideType = false
        hideUnavailable = false
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        hideActivity = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_WIFI_ACTIVITY, false)
        hideType = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_WIFI_TYPE, false)
        hideUnavailable = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_WIFI_UNAVAILABLE, false)
        if (!hideActivity && !hideType && !hideUnavailable) {
            DebugLog.hookSkipped(TAG, "WifiIcon", "no icon tuner wifi switches enabled")
            return
        }

        // 1. Substitute a connected WiFi model with the Hidden icon model. Mirrors Hyper Helper:
        // only when the caller's `showExclamation`-style flag (arg 4) is false, and the model is
        // an Active one, so genuinely unavailable WiFi still renders its own state.
        if (hideUnavailable) {
            val companionClass = "$WIFI_ICON_CLASS\$Companion".toClassOrNull()
            val activeClass = ACTIVE_CLASS.toClassOrNull()
            val hiddenInstance = runCatching {
                HIDDEN_CLASS.toClass().getField("INSTANCE").get(null)
            }.getOrNull()
            val fromModel = companionClass?.findMethodOrNull { name("fromModel") }
            if (fromModel == null || activeClass == null || hiddenInstance == null) {
                DebugLog.hookSkipped(
                    TAG,
                    "$WIFI_ICON_CLASS\$Companion#fromModel",
                    "method/class/INSTANCE not found"
                )
            } else {
                fromModel.hook {
                    before { param ->
                        val model = param.args.getOrNull(0)
                        val arg4 = param.args.getOrNull(4)
                        val noFlag = arg4 !is Boolean || !arg4
                        if (activeClass.isInstance(model) && noFlag) {
                            param.result = hiddenInstance
                        }
                    }
                }
            }
        }

        // 2. The factory assigns the fields after construction. Hook the getters, the stable
        // read boundary used by MiuiWifiViewBinder, instead of writing fields that are clobbered.
        if (hideActivity || hideType) {
            val vmClass = VM_CLASS.toClassOrNull()
            if (vmClass == null) {
                DebugLog.hookSkipped(TAG, VM_CLASS, "class not found")
                return
            }
            val getters = buildList {
                if (hideActivity) add("activityInOutRes")
                if (hideType) add("wifiStandard")
            }
            getters.forEach { property ->
                val getter = "get" + property.replaceFirstChar { it.uppercase() }
                vmClass.findMethodOrNull { name(getter); noParams() }?.hook {
                    before { param -> param.result = IconTunerFlows.zeroFlow }
                } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#$getter", "method not found")
            }
        }
    }
}
