package com.takekazex.hypertweak.hook.rules.personalassistant

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.base.dex.DexRuntime

import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.InstallPolicy
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

    /** Skip DEX preparation while spoofing is disabled; first enable prepares a cold cache in the background. */
    override val installPolicy: InstallPolicy = InstallPolicy.WHEN_ENABLED

    override fun isFeatureEnabled(): Boolean =
        Preferences.getBoolean(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF, false)

    override fun onHook() {
        if (!isFeatureEnabled()) {
            DebugLog.hookSkippedDebug(TAG, "model spoof", "disabled")
            return
        }
        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, "model spoof", "source APK unavailable")
            return
        }
        prepareDexTargets(name = "personalassistant.model_spoof",
            resolve = { resolveMethod(apkPath) }, install = ::installMethod)
    }

    private fun installMethod(method: Method) {
        // The method is small enough that AOT may inline it; deoptimize so the hook actually fires.
        deoptimize(method)
        method.hook {
            after { param ->
                if (!Preferences.getBoolean(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF, false)) return@after
                val json = param.result as? JSONObject ?: return@after
                val model = Preferences.getString(
                    PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_MODEL,
                    PlatformSettingsKeys.DEFAULT_PA_MODEL_SPOOF_MODEL
                )
                val device = Preferences.getString(
                    PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_DEVICE,
                    PlatformSettingsKeys.DEFAULT_PA_MODEL_SPOOF_DEVICE
                )
                if (model.isNotBlank()) json.put(MODEL_KEY, model)
                if (device.isNotBlank()) json.put(DEVICE_KEY, device)
            }
        }
        DebugLog.d(TAG, "hooked DexKit model-spoof JSON builder ${method.toGenericString()}")
    }

    private fun resolveMethod(apkPath: String): Method? = DexRuntime.resolve(
        feature = object : com.takekazex.hypertweak.hook.base.dex.DexFeature {
            override val id = "personalassistant.model_spoof"
            override val schema = 1
        }, apkPath = apkPath, cacheDir = dexCacheDirectory, appInfo = hookParam.appInfo,
        compute = { session -> session.dexKit { bridge ->
            val candidate = bridge.findMethod { matcher {
                paramTypes(Context::class.java, String::class.java)
                returnType(JSONObject::class.java)
                addUsingString(MODEL_KEY, StringMatchType.Equals)
                addUsingString(DEVICE_KEY, StringMatchType.Equals)
            } }.singleOrNull()
            candidate?.let { mapOf("method" to it.descriptor) }.orEmpty()
        }.orEmpty() },
        decode = { values, _ -> values["method"]?.let {
            com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec.method(it, classLoader)
        }?.takeIf { it.parameterTypes.contentEquals(arrayOf(Context::class.java, String::class.java)) &&
            it.returnType == JSONObject::class.java } },
    )
}
