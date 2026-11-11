package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSettingsPreferencePolicy

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
 * `MiuiMobileIconBinder` — reads them through the `MiuiMobileIconViewModel` getters. Each getter
 * returns the host flow combined with a [LiveFlowGate], which emits the hidden value of the
 * expected type while its option is on. The fields are assigned *after*
 * construction by the factory `MiuiMobileIconVMImpl$$ExternalSyntheticLambda0.invoke()` (verified
 * in smali/jadx on OS4.0.0.15.XPMCNXM), so an after-constructor write — what upstream does — is
 * clobbered right after it lands and has no effect on OS4; forcing the getters instead covers the
 * real read path. Roam visibility is split across the VM getters (`getMobileRoamVisible`,
 * `getSmallRoamVisible`) and a `StatusBarIconObserver.roamSettingBlock` field for the global
 * block. Global hiding now shares the native roaming getter boundaries instead of overwriting
 * the observer field. The getters stay hooked while every option is off, so a settings change only
 * flips a gate and the bound binders re-emit; only a freshly installed generation needs the icon
 * groups rebuilt to reach binders that collected the previous generation's flows.
 */
object CellularIconHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingIconHideCellularActivity by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ACTIVITY, false)
    }
    private val settingIconHideCellularType by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_TYPE, false)
    }
    private val nativeRoamingMode by hookSetting(0) {
        IconSettingsPreferencePolicy.nativeRoamingMode(
            IconSettingsKeys.KEY_ICON_CELLULAR_ROAM_MODE.let { key ->
                if (Preferences.contains(key)) Preferences.getInt(key, 0) else null
            },
            Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ROAM, false),
            Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_SMALL_ROAM, false),
            Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ROAM_GLOBAL, false)
        )
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

    private val separateType by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE, false)
    }

    /** One gate per option; every getter stays hooked, so a toggle only flips its gate. */
    private class Gates {
        val activity = LiveFlowGate("cellular activity", false)
        val type = LiveFlowGate("cellular type", false)
        val roam = LiveFlowGate("roaming", false)
        val smallRoam = LiveFlowGate("small roaming", false)
        val vowifi = LiveFlowGate("VoWiFi", false)
        val volte = LiveFlowGate("VoLTE", false)
        val volteNoService = LiveFlowGate("VoLTE no service", false)
        val speechHd = LiveFlowGate("HD voice", false)
    }

    private var gates: Gates? by generationState { null }

    private val pairName by lazy { IconTunerFlows.hostClassName("kotlin", "Pair") }
    private val hiddenPair by lazy { IconTunerFlows.createPair(false, false) }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        // 1. ViewModel visibility getters: the factory assigns the fields after construction, so
        //    the getters are the only read path (impl transformLatest lambdas + binder). Each one
        //    returns the host flow combined with its gate.
        val vmClass = VM_CLASS.toClassOrNull()
        if (vmClass == null) {
            DebugLog.hookSkipped(TAG, VM_CLASS, "class not found")
            return
        }
        val gates = Gates().also { gates = it }
        applySettings(gates)
        val visibility = listOf(
            "getInOutVisible" to gates.activity,
            "getMobileTypeVisible" to gates.type,
            "getMobileTypeImageVisible" to gates.type,
            "getMobileRoamVisible" to gates.roam,
            "getSmallRoamVisible" to gates.smallRoam,
            "getVowifiVisible" to gates.vowifi,
            "getVolteNoService" to gates.volteNoService,
            "getSpeechHd" to gates.speechHd,
            "getVolteVisibleGlobal" to gates.volte
        )
        var hooked = 0
        visibility.forEach { (getter, gate) ->
            val method = vmClass.findMethodOrNull { name(getter); noParams() }
            if (method == null) {
                DebugLog.hookSkipped(TAG, "$VM_CLASS#$getter", "method not found")
                return@forEach
            }
            if (getter == "getMobileTypeVisible" || getter == "getMobileTypeImageVisible") deoptimize(method)
            val returnType = method.returnType
            method.hook {
                after { param ->
                    if (param.throwable != null) return@after
                    val original = param.result ?: return@after
                    gate.wrap(original, returnType, ::hiddenValue)?.let { param.result = it }
                }
            }
            hooked++
        }
        DebugLog.hookRegistered(TAG, "CellularIcon live gates getters=$hooked ${describe(gates)}")
    }

    /**
     * Applies changed cellular options to the bound icons: each gate re-emits through the host
     * binder that already collects it, so nothing is rebound.
     */
    fun refreshSettings() {
        val current = gates ?: return
        applySettings(current)
        DebugLog.i(TAG, "CellularIcon live gates refreshed ${describe(current)}")
    }

    private fun applySettings(gates: Gates) {
        gates.activity.set(settingIconHideCellularActivity)
        gates.type.set(settingIconHideCellularType || separateType)
        gates.roam.set(nativeRoamingMode and 1 != 0)
        gates.smallRoam.set(nativeRoamingMode and 2 != 0)
        gates.vowifi.set(settingIconHideCellularVowifi)
        gates.volte.set(settingIconHideCellularVolte)
        gates.volteNoService.set(settingIconHideCellularVolteNoService)
        gates.speechHd.set(settingIconHideCellularSpeechHd)
    }

    /** Hidden value of the getter's own emission type. */
    private fun hiddenValue(value: Any?): Any? = when {
        value is Boolean -> false
        // The international VoLTE binder casts emissions to Pair and uses the second flag.
        value?.javaClass?.name == pairName && MobileSignalVisibility.pairValueOf(value) != null -> hiddenPair ?: value
        else -> value
    }

    private fun describe(gates: Gates) = "activity=${gates.activity.hidden} type=${gates.type.hidden} " +
        "roam=${gates.roam.hidden}/${gates.smallRoam.hidden} vowifi=${gates.vowifi.hidden} " +
        "volte=${gates.volte.hidden} noService=${gates.volteNoService.hidden} hd=${gates.speechHd.hidden}"
}
