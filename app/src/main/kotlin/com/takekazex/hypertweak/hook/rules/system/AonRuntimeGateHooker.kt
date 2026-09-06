package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.DebugLog

/**
 * Forces the AON runtime capability resources in system_server so the sensor services actually
 * act on the Settings toggles revealed by [com.takekazex.hypertweak.hook.rules.settings.VisualPerceptionSettingsHooker].
 *
 * The Settings-side unlock only reveals rows; the feature code that consumes them lives in
 * `miui-services.jar` inside system_server and gates on `android.miui` resource bools read through
 * `Resources#getBoolean(int)` at boot (see docs/FEATURE_DETAIL.md, "AON Visual Perception &
 * Air-Gesture Unlocks"). We force only the ids of the three visual-perception gates:
 *  - 0x110500a0 = config_aon_anti_burn_available  → AntiburnScreenController (非注视感知)
 *  - 0x110500a3 = config_aon_screen_off_available  → MiuiAttentionDetector (感知锁屏)
 *  - 0x110500a4 = config_aon_screen_on_available   → MiuiAttentionDetector (靠近亮屏)
 *
 * Ids are compile-time constants of the `android.miui` resource package; verified against
 * OS4.0.0.25.XPMCNXM device `miui-services.jar` and framework-ext-res `public.xml`. On other
 * builds a drifted id simply never matches and the hook is a no-op (original behaviour).
 *
 * Effect is gated by the 解锁更多主动视觉感知 preference read per call; values apply from the next
 * boot of system_server (both controllers read the resource once during their construction).
 */
object AonRuntimeGateHooker : StaticHooker() {
    private const val TAG = "AonRuntimeGate"

    /** config_aon_anti_burn_available (0x110500a0). */
    private const val ID_ANTI_BURN = 0x110500a0

    /** config_aon_screen_off_available (0x110500a3). */
    private const val ID_AON_SCREEN_OFF = 0x110500a3

    /** config_aon_screen_on_available (0x110500a4). */
    private const val ID_AON_SCREEN_ON = 0x110500a4

    private val visualPerceptionIds = intArrayOf(ID_ANTI_BURN, ID_AON_SCREEN_OFF, ID_AON_SCREEN_ON)

    override fun onHook() {
        val resources = "android.content.res.Resources".toClassOrNull() ?: return
        val method = runCatching {
            resources.getDeclaredMethod("getBoolean", Int::class.javaPrimitiveType)
        }.getOrNull() ?: return
        deoptimize(method)
        method.hook("aon_runtime_visual_gates") { after { param ->
            val id = param.args.getOrNull(0) as? Int ?: return@after
            if (id !in visualPerceptionIds) return@after
            if (param.result == true) return@after
            runCatching {
                if (Preferences.unlockMoreVisualPerception()) {
                    param.result = true
                }
            }.onFailure { t ->
                DebugLog.w(TAG, "failed to force AON resource 0x${id.toString(16)}", t)
            }
        } }
        DebugLog.i(TAG, "forcing AON visual-perception runtime gates ${visualPerceptionIds.joinToString { "0x${it.toString(16)}" }}")
    }
}
