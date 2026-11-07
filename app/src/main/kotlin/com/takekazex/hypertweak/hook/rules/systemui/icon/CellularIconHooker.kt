package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.settings.contract.IconSettingsKeys

import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * Status-bar cellular icon visibility, ported from Hyper Helper's `CellularIcon` hooker
 * (see cache/xiaomihelper-2bfd4873a4138764, OS4 comparison in OS4_ADAPTATION_PLAN.md T3/T4).
 *
 * `MiuiCellularIconVM` exposes its visibility flags as `ReadonlyStateFlow` fields; every
 * consumer — the per-SIM `MiuiMobileIconVMImpl`'s `transformLatest` lambdas and
 * `MiuiMobileIconBinder` — reads them through the `MiuiMobileIconViewModel` getters. The hook
 * replaces enabled visibility flows with hidden values of the expected type. The fields are assigned *after*
 * construction by the factory `MiuiMobileIconVMImpl$$ExternalSyntheticLambda0.invoke()` (verified
 * in smali/jadx on OS4.0.0.15.XPMCNXM), so an after-constructor write — what upstream does — is
 * clobbered right after it lands and has no effect on OS4; forcing the getters instead covers the
 * real read path. Roam visibility is split across the VM getters (`getMobileRoamVisible`,
 * `getSmallRoamVisible`) and a `StatusBarIconObserver.roamSettingBlock` field for the global
 * block. Global hiding now shares the native roaming getter boundaries instead of overwriting
 * the observer field; rebuilding native icon groups reconnects existing binder collections.
 */
object CellularIconHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingIconHideCellularActivity by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ACTIVITY, false)
    }
    private val settingIconHideCellularType by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_TYPE, false)
    }
    private val settingIconHideCellularRoamGlobal by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ROAM_GLOBAL, false)
    }
    private val settingIconHideCellularRoam by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ROAM, false)
    }
    private val settingIconHideCellularSmallRoam by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_SMALL_ROAM, false)
    }
    private val settingIconHideCellularVowifi by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOWIFI, false)
    }
    private val settingIconHideCellularVolte by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE, false)
    }
    private val settingIconHideCellularVolteNoService by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE, false)
    }
    private val settingIconHideCellularSpeechHd by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_SPEECH_HD, false)
    }

    private const val TAG = "IconTuner"
    private const val VM_CLASS = "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MiuiCellularIconVM"

    private var hideActivity by generationState { false }

    private var hideType by generationState { false }

    private var hideRoamGlobal by generationState { false }

    private var hideRoam by generationState { false }

    private var hideSmallRoam by generationState { false }

    private var hideVoWifi by generationState { false }

    private var hideVolte by generationState { false }

    private var hideVolteNoService by generationState { false }

    private var hideSpeechHd by generationState { false }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        hideActivity = settingIconHideCellularActivity
        hideType = settingIconHideCellularType
        hideRoamGlobal = settingIconHideCellularRoamGlobal
        hideRoam = settingIconHideCellularRoam
        hideSmallRoam = settingIconHideCellularSmallRoam
        hideVoWifi = settingIconHideCellularVowifi
        hideVolte = settingIconHideCellularVolte
        hideVolteNoService = settingIconHideCellularVolteNoService
        hideSpeechHd = settingIconHideCellularSpeechHd

        val anyEnabled = hideActivity || hideType || hideRoamGlobal || hideRoam || hideSmallRoam ||
            hideVoWifi || hideVolte || hideVolteNoService || hideSpeechHd
        if (!anyEnabled) {
            DebugLog.hookSkippedDebug(TAG, "CellularIcon", "no icon tuner cellular switches enabled")
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
            if (hideVolteNoService) add("getVolteNoService")
            if (hideSpeechHd) add("getSpeechHd")
        }
        DebugLog.d(TAG, "CellularIcon installing: getters=$getters roam=$hideRoam smallRoam=$hideSmallRoam roamGlobal=$hideRoamGlobal")
        getters.forEach { getter ->
            vmClass.findMethodOrNull { name(getter) }?.hook {
                before { param -> param.result = IconTunerFlows.falseFlow }
            } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#$getter", "method not found")
        }
        if (hideVolte) hookVolteVisibility(vmClass)

        // 2. Roam indicator methods return the flow directly.
        if (hideRoam || hideRoamGlobal) {
            vmClass.findMethodOrNull { name("getMobileRoamVisible") }?.hook {
                before { param -> param.result = IconTunerFlows.falseFlow }
            } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#getMobileRoamVisible", "method not found")
        }
        if (hideSmallRoam || hideRoamGlobal) {
            vmClass.findMethodOrNull { name("getSmallRoamVisible") }?.hook {
                before { param -> param.result = IconTunerFlows.falseFlow }
            } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#getSmallRoamVisible", "method not found")
        }

        // Global roaming applies to both native roaming consumers. Keep the observer's original
        // flow intact so disabling this switch and rebuilding binders restores host ownership.
    }

    private fun hookVolteVisibility(vmClass: Class<*>) {
        val getterName = "getVolteVisibleGlobal"
        val flowType = IconTunerFlows.hostClassName("kotlinx.coroutines.flow", "Flow").toClassOrNull()
        val getter = vmClass.findMethodOrNull { name(getterName); noParams() }
        if (flowType == null || getter == null || !flowType.isAssignableFrom(getter.returnType)) {
            DebugLog.hookSkipped(TAG, "$VM_CLASS#$getterName", "unsupported VoLTE getter")
            return
        }

        val pairName = IconTunerFlows.hostClassName("kotlin", "Pair")
        val hiddenPairFlow by lazy {
            runCatching {
                val pair = checkNotNull(IconTunerFlows.createPair(false, false))
                IconTunerFlows.createReadonlyStateFlow(pair)
            }.onFailure { DebugLog.w(TAG, "could not create hidden VoLTE Pair flow", it) }.getOrNull()
        }
        getter.hook {
            after { param ->
                if (param.throwable != null) return@after
                val original = param.result ?: return@after
                val value = IconTunerFlows.readFlowValue(original)
                val replacement = when {
                    value is Boolean -> IconTunerFlows.falseFlow
                    value?.javaClass?.name == pairName &&
                        MobileSignalVisibility.pairValueOf(value) != null -> hiddenPairFlow
                    else -> null
                }
                // The international binder casts emissions to Pair and uses the second flag for visibility.
                if (getter.returnType.isInstance(replacement)) param.result = replacement
            }
        }
    }
}
