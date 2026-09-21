package com.takekazex.hypertweak.hook.rules.personalassistant

import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import org.json.JSONObject

/**
 * 机型伪装 (device-model spoof) for the Smart Assistant (`com.miui.personalassistant`).
 *
 * The "智能测算" MAML suit (which contains the 精准电量 widget) is delivered by Xiaomi's
 * assistant/theme server only to devices that report a supported model — the 澎湃G1 battery-chip
 * family (e.g. Xiaomi 12S Ultra, `model=2203121C`, `device=thor`). The Smart Assistant builds the
 * environment signal for each request and puts `phoneModel` (`Build.MODEL`) and `phoneDevice`
 * (`Build.DEVICE`) into the JSON body (verified in reverse cache
 * `personalassistant-dbbd0a27b105e68c`).
 *
 * This hooker re-writes those two fields on the built [JSONObject] so the server believes the
 * request comes from a G1 device and pushes the suit down (search → add → MAML download from the
 * live theme-market URL). Both values are read live on every call, so toggling the switch or
 * editing the values takes effect on the next request without an assistant restart.
 *
 * Scoped to `com.miui.personalassistant` (declared in `scope.list` + `arrays.xml`). The target is
 * resolved from the two JSON keys and method prototype on each APK fingerprint;
 * no obfuscated class or method name is carried between Smart Assistant releases.
 */
object ModelSpoofHooker : StaticHooker() {

    private const val TAG = "ModelSpoof"
    private const val MODEL_KEY = "phoneModel"
    private const val DEVICE_KEY = "phoneDevice"

    override fun onHook() {
        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, "model spoof", "source APK unavailable")
            return
        }
        val method = resolveMethod(apkPath) ?: run {
            DebugLog.hookSkipped(TAG, "model spoof JSON builder", "unique DexKit target not found")
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
                if (model.isNotBlank()) json.put(MODEL_KEY, model)
                if (device.isNotBlank()) json.put(DEVICE_KEY, device)
            }
        }
        DebugLog.d(TAG, "hooked DexKit model-spoof JSON builder ${method.toGenericString()}")
    }

    private fun resolveMethod(apkPath: String): Method? = DexKitManager.withBridge(apkPath) { bridge ->
        val candidates = bridge.findMethod {
            matcher {
                paramTypes(Context::class.java, String::class.java)
                returnType(JSONObject::class.java)
                addUsingString(MODEL_KEY, StringMatchType.Equals)
                addUsingString(DEVICE_KEY, StringMatchType.Equals)
            }
        }.toList()

        candidates.mapNotNull(::materialize)
            .filter { method ->
                method.parameterTypes.contentEquals(arrayOf(Context::class.java, String::class.java)) &&
                    method.returnType == JSONObject::class.java
            }
            .singleOrNull()
    }

    private fun materialize(data: MethodData): Method? = runCatching {
        data.getMethodInstance(classLoader)
    }.onFailure {
        DebugLog.w(TAG, "failed to inspect ${data.className}#${data.methodName}", it)
    }.getOrNull()
}
