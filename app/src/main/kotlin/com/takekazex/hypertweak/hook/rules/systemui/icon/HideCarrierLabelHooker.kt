package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys

import com.takekazex.hypertweak.hook.base.HostAccess
import android.view.View
import android.widget.TextView
import android.telephony.SubscriptionManager
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility
import com.takekazex.hypertweak.settings.contract.SimVisibilityPolicy
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences

import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.util.WeakHashMap
import androidx.core.view.isGone

/**
 * Carrier label hiding, ported from Hyper Helper's `HideCarrierLabel` (OS4_ADAPTATION_PLAN.md T7).
 *
 * On OS4 the carrier rows live in `ControlCenterCarrierText` (slot `innerCarrierSlotId` 0/1) inside
 * `MiuiCarrierTextLayout`, which is reused for the control-center header and the lockscreen header
 * (`isKeyguardLayout`). The hook hides the row's `carrierTextView` right after the layout is built
 * and again on every live `onCarrierTextChanged` update, and forces the HD icon hidden after
 * `updateHDText`. `shouldShow()` reads the text view visibility, so a hidden row is fully treated
 * as absent (separator and width logic follow).
 *
 * The unified SIM policy hides control-center rows; lockscreen labels remain independent.
 * MobileSignalHub re-applies the non-default SIM rule when the active data subscription changes.
 */
internal object HideCarrierLabelHooker : IconRenderer() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val simVisibility by hookSetting(NetworkIconVisibility()) { IconTunerOptions.networkVisibility() }
    private val settingIconHideCarrierHd by hookSetting(false) { Preferences.iconConfig().boolean(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_HD) }
    private val settingIconHideLsCarrierOne by hookSetting(false) {
        Preferences.iconConfig().boolean(IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_ONE)
    }
    private val settingIconHideLsCarrierTwo by hookSetting(false) {
        Preferences.iconConfig().boolean(IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_TWO)
    }

    init {
        hostRecovery(HostRecoveryPhase.ICONS, icons = true) {
            recoverExistingViews(it.views)
        }
    }

    private const val TAG = "IconTuner"
    private const val LAYOUT_CLASS = "com.android.systemui.controlcenter.shade.MiuiCarrierTextLayout"
    private const val CC_TEXT_CLASS = "com.android.systemui.controlcenter.shade.ControlCenterCarrierText"
    private const val CC_CALLBACK_CLASS =
        "com.android.systemui.controlcenter.shade.ControlCenterCarrierText\$mCarrierTextCallback\$1"

    private val knownRows = generationMainResource(WeakHashMap<View, Boolean>()) { it.clear() }
    private var hideHd by generationState { false }
    private var hideLsOne by generationState { false }
    private var hideLsTwo by generationState { false }

    private val hiddenViews = generationMainResource(WeakHashMap<View, Int>()) { it.clear() }
    private fun hideView(view: View) {
        if (!hiddenViews.containsKey(view) || view.visibility != View.GONE) hiddenViews[view] = view.visibility
        view.visibility = View.GONE
    }

    // Reflection cache.
    private var leftTextField: Field? by generationState { null }
    private var rightTextField: Field? by generationState { null }
    private var keyguardLayoutField: Field? by generationState { null }
    private var carrierTextViewField: Field? by generationState { null }
    private var hdTextField: Field? by generationState { null }
    private var plusTextField: Field? by generationState { null }
    private var callbackOwnerField: Field? by generationState { null }

    override fun onPrepareHotReload() {
        HostAccess.onMain {
            hiddenViews.forEach { (view, visibility) -> if (view.isGone) view.visibility = visibility }
            hiddenViews.clear()
        }
    }

    override fun onHook() {
        hideHd = settingIconHideCarrierHd
        hideLsOne = settingIconHideLsCarrierOne
        hideLsTwo = settingIconHideLsCarrierTwo
        val hidesControlCenter = listOf(simVisibility.simOneMode, simVisibility.simTwoMode,
            simVisibility.nonDefaultSimMode).contains(SimVisibilityPolicy.ALL)
        if (!hidesControlCenter && !hideHd && !hideLsOne && !hideLsTwo) {
            DebugLog.hookSkippedDebug(TAG, "HideCarrierLabel", "disabled")
            return
        }

        val ccTextClass = CC_TEXT_CLASS.toClassOrNull()
        if (ccTextClass != null) {
            carrierTextViewField = hierarchyField(ccTextClass, "carrierTextView")
            hdTextField = hierarchyField(ccTextClass, "hdText")
            plusTextField = hierarchyField(ccTextClass, "plusText")
            keyguardLayoutField = hierarchyField(ccTextClass, "isKeyguardLayout")
        } else {
            DebugLog.hookSkipped(TAG, CC_TEXT_CLASS, "class not found")
        }

        MobileSignalHub.subscribe(this) {
            knownRows.keys.toList().forEach(::hideRequestedRow)
        }

        // 1. MiuiCarrierTextLayout construction — hide the carrier rows for this surface.
        val layoutClass = LAYOUT_CLASS.toClassOrNull()
        if (layoutClass == null) {
            DebugLog.hookSkipped(TAG, LAYOUT_CLASS, "class not found")
        } else {
            leftTextField = hierarchyField(layoutClass, "leftCarrierTextView")
            rightTextField = hierarchyField(layoutClass, "rightCarrierTextView")
            if (leftTextField == null || rightTextField == null || carrierTextViewField == null) {
                DebugLog.hookSkipped(TAG, LAYOUT_CLASS, "carrier text view fields not found")
            } else {
                layoutClass.observeConstructors {
                    after { param ->
                        val layout = param.thisObject
                        hideRow(leftTextField, layout)
                        hideRow(rightTextField, layout)
                    }
                }
            }
        }

        // 2. HD icon — force hidden after every HD-state update.
        if (hideHd || hidesControlCenter) {
            val ccClass = ccTextClass
            if (ccClass != null && hdTextField != null && plusTextField != null) {
                ccClass.findMethodOrNull { name("updateHDText"); paramCount(2) }?.let { method ->
                    method.observe {
                        after { param ->
                            val text = param.thisObject
                            runCatching {
                                if (hideHd) {
                                    (hdTextField?.get(text) as? View)?.let(::hideView)
                                    (plusTextField?.get(text) as? View)?.let(::hideView)
                                }
                                hideRequestedRow(text)
                            }.onFailure { t ->
                                DebugLog.w(TAG, "HideCarrierLabel HD hide failed", t)
                            }
                        }
                    }
                } ?: DebugLog.hookSkipped(TAG, "$CC_TEXT_CLASS#updateHDText", "method not found")
            }
        }

        // 3. Live carrier text updates — the callback re-shows the row, so hide it again.
        val callbackClass = CC_CALLBACK_CLASS.toClassOrNull()
        if (callbackClass == null) {
            DebugLog.hookSkipped(TAG, CC_CALLBACK_CLASS, "class not found")
        } else {
            callbackOwnerField = hierarchyField(callbackClass, "this\$0")
            if (callbackOwnerField == null || carrierTextViewField == null) {
                DebugLog.hookSkipped(TAG, CC_CALLBACK_CLASS, "owner/carrier view fields not found")
            } else {
                callbackClass.findMethodOrNull { name("onCarrierTextChanged"); paramCount(3) }?.let { method ->
                    method.observe {
                        after { param ->
                            val callback = param.thisObject
                            val owner = runCatching { callbackOwnerField?.get(callback) }.getOrNull()
                                ?: return@after
                            hideRequestedRow(owner)
                        }
                    }
                } ?: DebugLog.hookSkipped(TAG, "$CC_CALLBACK_CLASS#onCarrierTextChanged", "method not found")
            }
        }
    }

    internal fun recoverExistingViews(views: List<View>) {
        views.filter { it.javaClass.name == CC_TEXT_CLASS }.forEach { row ->
            val controller = HostAccess.read(row, "carrierTextController")
            val callback = HostAccess.read(row, "mCarrierTextCallback")
            if (controller != null && callback != null) {
                controller.javaClass.methods.singleOrNull {
                    it.name == "addCallback" && it.parameterCount == 1 && it.parameterTypes[0].isInstance(callback)
                }?.invoke(controller, callback)
            }
        }
        views.filter { it.javaClass.name == LAYOUT_CLASS }.forEach { layout ->
            hideRow(leftTextField, layout)
            hideRow(rightTextField, layout)
        }
        if (hideHd) views.filter { it.javaClass.name == CC_TEXT_CLASS }.forEach { row ->
            (hdTextField?.get(row) as? View)?.let(::hideView)
            (plusTextField?.get(row) as? View)?.let(::hideView)
        }
    }

    private fun hideRow(textField: Field?, layout: Any) {
        if (textField == null) return
        val row = runCatching { textField.get(layout) }.getOrNull() ?: return
        hideRequestedRow(row)
    }

    private fun hideRequestedRow(row: Any) {
        (row as? View)?.let { knownRows[it] = true }
        val isKeyguard = runCatching { keyguardLayoutField?.getBoolean(row) }.getOrNull() ?: return
        val slotId = runCatching { HostAccess.requireDeclaredField(row.javaClass, "innerCarrierSlotId", accessible = true) }
            .getOrNull()?.let { runCatching { it.getInt(row) }.getOrDefault(-1) } ?: -1
        // Airplane mode shows one row (slot 1) that names no SIM, so per-SIM hiding never applies.
        val airplaneRow = (runCatching { carrierTextViewField?.get(row) }.getOrNull() as? TextView)
            ?.let(AirplaneLabelText::isAirplaneLabel) == true
        val hide = !airplaneRow && if (isKeyguard) {
            when (slotId) {
                0 -> hideLsOne
                1 -> hideLsTwo
                else -> false
            }
        } else {
            simVisibility.hidesCarrier(slotId, MobileSignalHub.state) { subId ->
                runCatching { SubscriptionManager.getSlotIndex(subId) }.getOrDefault(-1)
            }
        }
        runCatching {
            val text = carrierTextViewField?.get(row) as? View ?: return
            // The host callback already re-shows its nonempty airplane label. Drop our old
            // SIM suppression without restoring over another owner's newer GONE request.
            if (airplaneRow) hiddenViews.remove(text)
            else if (hide) hideView(text) else restoreView(text)
            if (!isKeyguard) listOf(hdTextField, plusTextField).forEach { field ->
                (field?.get(row) as? View)?.let { view ->
                    if (hide || hideHd) hideView(view) else restoreView(view)
                }
            }
        }.onFailure { DebugLog.w(TAG, "HideCarrierLabel carrier visibility failed", it) }
    }

    private fun restoreView(view: View) {
        hiddenViews.remove(view)?.let { visibility ->
            if (view.isGone) view.visibility = visibility
        }
    }

    private fun hierarchyField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            runCatching {
                return HostAccess.requireDeclaredField(c, name, accessible = true)
            }
            c = c.superclass
        }
        return null
    }
}
