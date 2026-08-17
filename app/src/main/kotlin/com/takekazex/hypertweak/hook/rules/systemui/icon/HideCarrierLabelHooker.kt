package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field

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
 * Keys match upstream w22.* (status bar / control center) and x22.e-f (lockscreen). Requires a
 * SystemUI restart.
 */
object HideCarrierLabelHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val LAYOUT_CLASS = "com.android.systemui.controlcenter.shade.MiuiCarrierTextLayout"
    private const val CC_TEXT_CLASS = "com.android.systemui.controlcenter.shade.ControlCenterCarrierText"
    private const val CC_CALLBACK_CLASS =
        "com.android.systemui.controlcenter.shade.ControlCenterCarrierText\$mCarrierTextCallback\$1"

    @Volatile private var hideOne = false
    @Volatile private var hideTwo = false
    @Volatile private var hideHd = false
    @Volatile private var hideLsOne = false
    @Volatile private var hideLsTwo = false

    // Reflection cache.
    private var leftTextField: Field? = null
    private var rightTextField: Field? = null
    private var keyguardLayoutField: Field? = null
    private var carrierTextViewField: Field? = null
    private var hdTextField: Field? = null
    private var plusTextField: Field? = null
    private var callbackOwnerField: Field? = null

    override fun onPrepareHotReload() {
        hideOne = false
        hideTwo = false
        hideHd = false
        hideLsOne = false
        hideLsTwo = false
    }

    override fun onHook() {
        hideOne = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_CARRIER_ONE, false)
        hideTwo = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_CARRIER_TWO, false)
        hideHd = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_CARRIER_HD, false)
        hideLsOne = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_LS_CARRIER_ONE, false)
        hideLsTwo = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_LS_CARRIER_TWO, false)
        if (!hideOne && !hideTwo && !hideHd && !hideLsOne && !hideLsTwo) {
            DebugLog.hookSkipped(TAG, "HideCarrierLabel", "disabled")
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
                layoutClass.hookAllConstructors {
                    after { param ->
                        val layout = param.thisObject ?: return@after
                        hideRow(leftTextField, layout)
                        hideRow(rightTextField, layout)
                    }
                }
            }
        }

        // 2. HD icon — force hidden after every HD-state update.
        if (hideHd) {
            val ccClass = ccTextClass
            if (ccClass != null && hdTextField != null && plusTextField != null) {
                ccClass.findMethodOrNull { name("updateHDText"); paramCount(2) }?.let { method ->
                    method.hook {
                        after { param ->
                            val text = param.thisObject ?: return@after
                            runCatching {
                                (hdTextField?.get(text) as? View)?.visibility = View.GONE
                                (plusTextField?.get(text) as? View)?.visibility = View.GONE
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
                    method.hook {
                        after { param ->
                            val callback = param.thisObject ?: return@after
                            val slotId = param.args.getOrNull(2) as? Int ?: return@after
                            val hide = when (slotId) {
                                0 -> hideOne
                                1 -> hideTwo
                                else -> false
                            }
                            if (!hide) return@after
                            val owner = runCatching { callbackOwnerField?.get(callback) }.getOrNull()
                                ?: return@after
                            hideCarrierText(owner)
                        }
                    }
                } ?: DebugLog.hookSkipped(TAG, "$CC_CALLBACK_CLASS#onCarrierTextChanged", "method not found")
            }
        }
    }

    private fun hideRow(textField: Field?, layout: Any) {
        if (textField == null) return
        val row = runCatching { textField.get(layout) }.getOrNull() ?: return
        val isKeyguard = runCatching { keyguardLayoutField?.getBoolean(row) }.getOrNull() ?: false
        val slotId = runCatching { row.javaClass.getDeclaredField("innerCarrierSlotId").apply { isAccessible = true } }
            .getOrNull()?.let { runCatching { it.getInt(row) }.getOrDefault(-1) } ?: -1
        val hide = if (isKeyguard) {
            when (slotId) {
                0 -> hideLsOne
                1 -> hideLsTwo
                else -> false
            }
        } else {
            when (slotId) {
                0 -> hideOne
                1 -> hideTwo
                else -> false
            }
        }
        if (hide) hideCarrierText(row)
    }

    private fun hideCarrierText(row: Any) {
        runCatching {
            val view = carrierTextViewField?.get(row) as? View ?: return
            view.visibility = View.GONE
        }.onFailure { t ->
            DebugLog.w(TAG, "HideCarrierLabel carrier hide failed", t)
        }
    }

    private fun hierarchyField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            runCatching {
                return c.getDeclaredField(name).apply { isAccessible = true }
            }
            c = c.superclass
        }
        return null
    }
}