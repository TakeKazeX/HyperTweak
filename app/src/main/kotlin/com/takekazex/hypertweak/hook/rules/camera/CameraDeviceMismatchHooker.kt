package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.settings.contract.CameraSettingsKeys

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Suppresses only the host's device-config mismatch exit when the user enabled compatibility.
 *
 * The gate must be installed **before** the host reads it during its own config-provider create,
 * so this resolution cannot move to the background. It was briefly deferred there to dodge
 * ActivityManager's 10 s provider deadline on this 205 MB APK, and the result was a gate installed
 * after the provider had already read it: the feature silently stopped working instead of getting
 * faster.
 *
 * The cost was in the wrong place. The full [CameraHostProfile.resolve] builds the dexlib2 operand
 * index (`CameraSemantics` -> `DexOperandIndex`), which enumerates every method in the APK (188k on
 * this build) and measured 577-773 ms idle but several seconds during boot, when the 205 MB APK is
 * read cold under IO contention. The gate needs none of that: only the config facade and its
 * config type, both of which DexKit answers directly. See [CameraHostProfile.resolveGateFacade].
 */
object CameraDeviceMismatchHooker : StaticHooker() {
    private const val TAG = "CamDeviceMismatch"
    private const val PACKAGE = "com.android.camera"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val started = android.os.SystemClock.uptimeMillis()
        val (facade, configType) = CameraHostProfile.resolveGateFacade(ctx, TAG) ?: return
        val gate = CameraResolver.resolveConfigFallbackGate(ctx, facade, configType, TAG)
        if (gate == null) return

        deoptimize(gate)
        gate.hook("cam_device_mismatch_gate") {
            after { param ->
                runCatching {
                    if ((Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_IGNORE_DEVICE_MISMATCH, false) ||
                            CameraLegendaryProfileState.selectedProfileApplied()) &&
                        param.result == true
                    ) {
                        param.result = false
                        DebugLog.d(TAG, "device-config mismatch exit bypassed by user setting or selected camera profile")
                    }
                }.onFailure { t -> DebugLog.w(TAG, "mismatch callback failed", t) }
            }
        }
        DebugLog.i(TAG, "device mismatch gate hooked structurally at " + gate.declaringClass.name + "#" +
            gate.name + "() in " + (android.os.SystemClock.uptimeMillis() - started) + "ms")
    }
}
