package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Modifier

/** Suppresses only the host's device-config mismatch exit when the user enabled compatibility. */
object CameraDeviceMismatchHooker : StaticHooker() {
    private const val TAG = "CamDeviceMismatch"
    private const val PACKAGE = "com.android.camera"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        val profile = CameraHostProfile.resolve(
            CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG,
        ) ?: return
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val gate = profile.mismatchGate?.let { name ->
            CameraResolver.resolveMethod(
                scope = TAG,
                key = "camera_device_mismatch_gate",
                clazz = profile.facade,
                names = listOf(name),
                shape = {
                    Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                        it.returnType == java.lang.Boolean.TYPE
                },
            )
        } ?: CameraResolver.resolveConfigFallbackGate(ctx, profile.facade, profile.configType, TAG)
        if (gate == null) return

        deoptimize(gate)
        gate.hook("cam_device_mismatch_gate") {
            after { param ->
                runCatching {
                    if ((Preferences.getBoolean(Preferences.KEY_CAMERA_IGNORE_DEVICE_MISMATCH, false) ||
                            CameraLegendaryProfileState.selectedProfileApplied()) &&
                        param.result == true
                    ) {
                        param.result = false
                        DebugLog.d(TAG, "device-config mismatch exit bypassed by user setting or selected camera profile")
                    }
                }.onFailure { t -> DebugLog.w(TAG, "mismatch callback failed", t) }
            }
        }
        DebugLog.d(TAG, "device mismatch gate hooked structurally at ${gate.declaringClass.name}#${gate.name}()")
    }
}
