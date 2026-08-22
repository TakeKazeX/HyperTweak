package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Status-bar cellular icon visibility, ported from Hyper Helper's `CellularIcon` hooker
 * (see cache/xiaomihelper-2bfd4873a4138764, OS4 comparison in OS4_ADAPTATION_PLAN.md T3/T4).
 *
 * `MiuiCellularIconVM` exposes its visibility flags as `ReadonlyStateFlow<Boolean>` fields; every
 * consumer — the per-SIM `MiuiMobileIconVMImpl`'s `transformLatest` lambdas and
 * `MiuiMobileIconBinder` — reads them through the `MiuiMobileIconViewModel` getters. The hook
 * forces the enabled getters to a shared `false` flow. NOTE: the fields are assigned *after*
 * construction by the factory `MiuiMobileIconVMImpl$$ExternalSyntheticLambda0.invoke()` (verified
 * in smali/jadx on OS4.0.0.15.XPMCNXM), so an after-constructor write — what upstream does — is
 * clobbered right after it lands and has no effect on OS4; forcing the getters instead covers the
 * real read path. Roam visibility is split across the VM getters (`getMobileRoamVisible`,
 * `getSmallRoamVisible`) and a `StatusBarIconObserver.roamSettingBlock` field for the global
 * block. Requires a SystemUI restart.
 */
object CellularIconHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val VM_CLASS = "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MiuiCellularIconVM"
    private const val OBSERVER_CLASS = "com.android.systemui.statusbar.policy.StatusBarIconObserver"

    @Volatile
    private var hideActivity = false
    @Volatile
    private var hideType = false
    @Volatile
    private var hideRoamGlobal = false
    @Volatile
    private var hideRoam = false
    @Volatile
    private var hideSmallRoam = false
    @Volatile
    private var hideVoWifi = false
    @Volatile
    private var hideVolte = false
    @Volatile
    private var hideVolteNoService = false
    @Volatile
    private var hideSpeechHd = false

    override fun onPrepareHotReload() {
        hideActivity = false
        hideType = false
        hideRoamGlobal = false
        hideRoam = false
        hideSmallRoam = false
        hideVoWifi = false
        hideVolte = false
        hideVolteNoService = false
        hideSpeechHd = false
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        hideActivity = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_CELLULAR_ACTIVITY, false)
        hideType = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_CELLULAR_TYPE, false)
        hideRoamGlobal = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_CELLULAR_ROAM_GLOBAL, false)
        hideRoam = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_CELLULAR_ROAM, false)
        hideSmallRoam = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_CELLULAR_SMALL_ROAM, false)
        hideVoWifi = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_CELLULAR_VOWIFI, false)
        hideVolte = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_CELLULAR_VOLTE, false)
        hideVolteNoService = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE, false)
        hideSpeechHd = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_CELLULAR_SPEECH_HD, false)

        val anyEnabled = hideActivity || hideType || hideRoamGlobal || hideRoam || hideSmallRoam ||
            hideVoWifi || hideVolte || hideVolteNoService || hideSpeechHd
        if (!anyEnabled) {
            DebugLog.hookSkipped(TAG, "CellularIcon", "no icon tuner cellular switches enabled")
            return
        }

        // 1. ViewModel visibility getters: force a shared false flow. The factory assigns the
        //    fields after construction, so the fields themselves cannot be replaced in a ctor
        //    hook; the getters are the only read path (impl transformLatest lambdas + binder).
        val vmClass = VM_CLASS.toClassOrNull()
        if (vmClass == null) {
            DebugLog.hookSkipped(TAG, VM_CLASS, "class not found")
            return
        }
        val getters = buildList {
            if (hideActivity) add("getInOutVisible")
            if (hideType) {
                add("getMobileTypeVisible")
                add("getMobileTypeImageVisible")
            }
            if (hideVoWifi) add("getVowifiVisible")
            if (hideVolte) add("getVolteVisibleGlobal")
            if (hideVolteNoService) add("getVolteNoService")
            if (hideSpeechHd) add("getSpeechHd")
        }
        DebugLog.i(TAG, "CellularIcon installing: getters=$getters roam=$hideRoam smallRoam=$hideSmallRoam roamGlobal=$hideRoamGlobal")
        getters.forEach { getter ->
            vmClass.findMethodOrNull { name(getter) }?.hook {
                before { param -> param.result = IconTunerFlows.falseFlow }
            } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#$getter", "method not found")
        }

        // 2. Roam indicator methods return the flow directly.
        if (hideRoam) {
            vmClass.findMethodOrNull { name("getMobileRoamVisible") }?.hook {
                before { param -> param.result = IconTunerFlows.falseFlow }
            } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#getMobileRoamVisible", "method not found")
        }
        if (hideSmallRoam) {
            vmClass.findMethodOrNull { name("getSmallRoamVisible") }?.hook {
                before { param -> param.result = IconTunerFlows.falseFlow }
            } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#getSmallRoamVisible", "method not found")
        }

        // 3. Global roam block lives on StatusBarIconObserver.
        if (hideRoamGlobal) {
            val observerClass = OBSERVER_CLASS.toClassOrNull()
            if (observerClass == null) {
                DebugLog.hookSkipped(TAG, OBSERVER_CLASS, "class not found")
                return
            }
            val roamField = runCatching { observerClass.getDeclaredField("roamSettingBlock") }
                .getOrNull()
            if (roamField == null) {
                DebugLog.hookSkipped(TAG, "$OBSERVER_CLASS#roamSettingBlock", "field not found")
                return
            }
            observerClass.hookAllConstructors {
                after { param ->
                    val observer = param.thisObject
                    runCatching {
                        IconTunerFlows.writeField(observer, roamField, IconTunerFlows.falseFlow)
                    }.onFailure { t ->
                        DebugLog.w(TAG, "CellularIcon failed to write roamSettingBlock", t)
                    }
                }
            }
        }
    }
}
