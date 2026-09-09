package com.takekazex.hypertweak.hook.rules.phone

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps HyperOS video ringback (CRBT) disabled in the Phone service.
 *
 * The Phone settings app removes `button_enable_crbt` when
 * `TelephonyManagerEx.shouldDisplayCrbtButton()` is false. On the current CN
 * build that result comes from `com.android.phone.FiveGManager`, which checks
 * the framework disable-CRBT capability and a cloud optimization gate. The
 * native `FiveGManagerBase.setCrbtDisable(boolean)` writes
 * `Settings.Global.button_crbt_mode` and modem feature 12, so forcing its
 * argument is the backend boundary that also survives cloud callbacks.
 */
object VideoRingbackHooker : StaticHooker() {
    private const val TAG = "VideoRingback"
    private const val PHONE_PACKAGE = "com.android.phone"
    private const val BASE_CLASS = "com.android.phone.FiveGManagerBase"
    private const val MANAGER_CLASS = "com.android.phone.FiveGManager"

    private val enforcementApplied = AtomicBoolean(false)
    private var setCrbtDisable: Method? = null

    override fun onPrepareHotReload() {
        enforcementApplied.set(false)
        setCrbtDisable = null
    }

    override fun onHook() {
        if (hookParam.packageName != PHONE_PACKAGE) return
        if (!Preferences.getBoolean(Preferences.KEY_DISABLE_VIDEO_RINGBACK, false)) {
            DebugLog.hookSkipped(TAG, "video ringback disable", "disabled")
            return
        }

        val base = BASE_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BASE_CLASS, "class not found")
            return
        }
        setCrbtDisable = base.declaredMethods.firstOrNull {
            it.name == "setCrbtDisable" &&
                it.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE)) &&
                it.returnType == Void.TYPE &&
                !Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true } ?: run {
            DebugLog.hookSkipped(TAG, "$BASE_CLASS#setCrbtDisable(boolean)", "method not found")
            return
        }

        hookSetCrbtDisable(setCrbtDisable!!)
        hookFactory()
        hookDisplayGate(base)
        MANAGER_CLASS.toClassOrNull()?.let(::hookDisplayGate)
        DebugLog.i(TAG, "video ringback disable enforcement armed in Phone service")
    }

    private fun hookSetCrbtDisable(method: Method) {
        deoptimize(method)
        method.hook("phone_video_ringback_force_disable") {
            before { param ->
                HookFailurePolicy.open(TAG, "setCrbtDisable.before", Unit) {
                    if (isEnabled()) param.args[0] = true
                }
            }
        }
    }

    /** Apply the native setter as soon as the concrete 5G manager is created. */
    private fun hookFactory() {
        val manager = MANAGER_CLASS.toClassOrNull() ?: run {
            DebugLog.w(TAG, "$MANAGER_CLASS not found; startup enforcement will use the display gate")
            return
        }
        val factory = manager.declaredMethods.firstOrNull {
            it.name == "make" &&
                Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.isEmpty() &&
                it.returnType.name == BASE_CLASS
        } ?: run {
            DebugLog.w(TAG, "$MANAGER_CLASS#make() not found; startup enforcement will use the display gate")
            return
        }
        deoptimize(factory)
        factory.hook("phone_video_ringback_factory_enforce") {
            after { param ->
                HookFailurePolicy.open(TAG, "FiveGManager.make.after", Unit) {
                    if (isEnabled()) enforceDisabled(param.result)
                }
            }
        }
    }

    /**
     * The base method is a fallback for a base-manager build; the concrete
     * override is the current implementation and is the path used by the
     * `MiuiPhoneInterfaceManager` binder method.
     */
    private fun hookDisplayGate(clazz: Class<*>) {
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "shouldDisplayCrbtButton" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == java.lang.Boolean.TYPE &&
                !Modifier.isStatic(it.modifiers)
        } ?: return
        deoptimize(method)
        method.hook("phone_video_ringback_display_${clazz.name}") {
            after { param ->
                HookFailurePolicy.open(TAG, "shouldDisplayCrbtButton.after", Unit) {
                    if (isEnabled()) {
                        enforceDisabled(param.thisObject)
                        param.result = true
                    }
                }
            }
        }
    }

    private fun enforceDisabled(instance: Any?) {
        if (instance == null || !isEnabled() || enforcementApplied.get()) return
        synchronized(enforcementApplied) {
            if (enforcementApplied.get()) return
            HookFailurePolicy.open(TAG, "setCrbtDisable(true)", Unit) {
                setCrbtDisable?.invoke(instance, true)
                enforcementApplied.set(true)
            }
        }
    }

    private fun isEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_DISABLE_VIDEO_RINGBACK, false)
}
