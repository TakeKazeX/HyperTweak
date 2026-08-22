package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hides the cellular icon of the non-default data SIM while the stacked-signal feature is off,
 * ported from Hyper Helper's `HideCellularIcon` hooker. `MobileIconsInteractorImpl` exposes the
 * default data subscription id; every `MobileIconViewModel` carries its own `subscriptionId`, so
 * the hook replaces the `isVisible` flow with a shared `false` flow when they differ. All targets
 * verified on OS4. Requires a SystemUI restart.
 */
object HideCellularIconHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val INTERACTOR_CLASS = "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl"
    private const val VM_CLASS = "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconViewModel"

    private val defaultDataSubId = AtomicInteger(Int.MIN_VALUE)

    override fun onPrepareHotReload() {
        defaultDataSubId.set(Int.MIN_VALUE)
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        val stackedEnabled = Preferences.getBoolean(Preferences.KEY_ICON_STACKED_ENABLED, false)
        val hideSimAuto = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_NON_DEFAULT_SIM, false)
        if (stackedEnabled || !hideSimAuto) {
            DebugLog.hookSkipped(
                TAG,
                "HideCellularIcon",
                if (stackedEnabled) "stacked signal owns the slot" else "hide sim auto off"
            )
            return
        }

        // Capture the default data sub id whenever the interactor resolves it.
        val interactorClass = INTERACTOR_CLASS.toClassOrNull()
        if (interactorClass == null) {
            DebugLog.hookSkipped(TAG, INTERACTOR_CLASS, "class not found")
            return
        }
        val defaultSubIdField = interactorClass.fieldOrNull("defaultDataSubId")
        if (defaultSubIdField == null) {
            DebugLog.hookSkipped(TAG, "$INTERACTOR_CLASS#defaultDataSubId", "field not found")
            return
        }
        interactorClass.findMethodOrNull { name("getMobileConnectionInteractorForSubId") }?.hook {
            after { param ->
                val interactor = param.thisObject
                val subId = runCatching { defaultSubIdField.get(interactor) as? Int }.getOrNull()
                if (subId != null) {
                    defaultDataSubId.set(subId)
                }
            }
        } ?: DebugLog.hookSkipped(
            TAG,
            "$INTERACTOR_CLASS#getMobileConnectionInteractorForSubId",
            "method not found"
        )

        // Hide the ViewModel whose subscription is not the default data one.
        val vmClass = VM_CLASS.toClassOrNull()
        if (vmClass == null) {
            DebugLog.hookSkipped(TAG, VM_CLASS, "class not found")
            return
        }
        val subscriptionField = vmClass.fieldOrNull("subscriptionId")
        val isVisibleField = vmClass.fieldOrNull("isVisible")
        if (subscriptionField == null || isVisibleField == null) {
            DebugLog.hookSkipped(TAG, "$VM_CLASS fields", "subscriptionId/isVisible not found")
            return
        }
        vmClass.hookAllConstructors {
            after { param ->
                val vm = param.thisObject
                val subId = runCatching { subscriptionField.get(vm) as? Int }.getOrNull()
                if (subId == null) return@after
                val default = defaultDataSubId.get()
                if (default != Int.MIN_VALUE && subId != default) {
                    runCatching {
                        IconTunerFlows.writeField(vm, isVisibleField, IconTunerFlows.falseFlow)
                    }.onFailure { t ->
                        DebugLog.w(TAG, "HideCellularIcon failed to write isVisible", t)
                    }
                }
            }
        }
    }

    private fun Class<*>.fieldOrNull(name: String): Field? =
        runCatching { getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
}
