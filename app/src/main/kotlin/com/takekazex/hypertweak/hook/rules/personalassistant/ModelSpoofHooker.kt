package com.takekazex.hypertweak.hook.rules.personalassistant

import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.json.JSONObject

/**
 * 机型伪装 (device-model spoof) for the Smart Assistant (`com.miui.personalassistant`).
 *
 * The "智能测算" MAML suit (which contains the 精准电量 widget) is delivered by Xiaomi's
 * assistant/theme server only to devices that report a supported model — the 澎湃G1 battery-chip
 * family (e.g. Xiaomi 12S Ultra, `model=2203121C`, `device=thor`). The Smart Assistant builds the
 * environment signal every request in `com.miui.personalassistant.network.util.a#a(Context, String)`
 * (the release obfuscated `CommonParamsUtil`, see reverse cache `personalassistant-dbbd0a27b105e68c`),
 * putting `phoneModel` (`Build.MODEL`) and `phoneDevice` (`Build.DEVICE`) into the JSON body.
 *
 * This hooker re-writes those two fields on the built [JSONObject] so the server believes the
 * request comes from a G1 device and pushes the suit down (search → add → MAML download from the
 * live theme-market URL). Both values are read live on every call, so toggling the switch or
 * editing the values takes effect on the next request without an assistant restart.
 *
 * Scoped to `com.miui.personalassistant` (declared in `scope.list` + `arrays.xml`). The hooked
 * class/method names are the release obfuscated names for Smart Assistant 25.40.31.00 — a future
 * OTA may rename them (log "class/method not found" and re-resolve by signature/DexKit then).
 */
object ModelSpoofHooker : StaticHooker() {

    /** Release obfuscated `CommonParamsUtil` (jadx: `network/util/a.java`). */
    private const val TARGET_CLASS = "com.miui.personalassistant.network.util.a"
    private const val TAG = "ModelSpoof"

    override fun onHook() {
        val clazz = TARGET_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, TARGET_CLASS, "class not found (renamed by a Smart Assistant OTA?)")
            return
        }
        val method = clazz.findMethodOrNull {
            name("a")
            parameterTypes(Context::class.java, String::class.java)
        } ?: run {
            DebugLog.hookSkipped(TAG, "$TARGET_CLASS#a(Context,String)", "method not found")
            return
        }

        // The method is small enough that AOT may inline it; deoptimize so the hook actually fires.
        deoptimize(method)
        method.hook {
            after { param ->
                if (!Preferences.getBoolean(Preferences.KEY_PA_MODEL_SPOOF, false)) return@after
                val json = param.result as? JSONObject ?: return@after
                val model = Preferences.getString(
                    Preferences.KEY_PA_MODEL_SPOOF_MODEL,
                    Preferences.DEFAULT_PA_MODEL_SPOOF_MODEL
                )
                val device = Preferences.getString(
                    Preferences.KEY_PA_MODEL_SPOOF_DEVICE,
                    Preferences.DEFAULT_PA_MODEL_SPOOF_DEVICE
                )
                if (model.isNotBlank()) json.put("phoneModel", model)
                if (device.isNotBlank()) json.put("phoneDevice", device)
            }
        }
        DebugLog.d(TAG, "hooked $TARGET_CLASS#a(Context,String)")
    }
}
