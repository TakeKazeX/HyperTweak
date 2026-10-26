package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.util.logging.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

private object CarrierOfflineFeature : DexFeature {
    override val id = "carrier.offline_getter"
    override val schema = 2
}

private const val TAG = "CarrierOfflineConfig"
private const val SECURITY_CENTER = "com.miui.securitycenter"
private const val CONTACTS = "com.android.contacts"
private const val NETWORK_ASSISTANT_MODEL = "com.miui.networkassistant.ui.bean.OffLineData\$BaseData"
private const val CONTACTS_BUSINESS_MODEL = "com.mobile.businesshall.bean.OffLineData\$BaseData"

private data class CarrierOfflineTargets(val getter: Method, val callers: List<Method>)

/** Overrides only the carrier offline getter; instance fields and native state remain untouched. */
object CarrierOfflineConfigHooker : DynamicHooker() {
    override fun onHook() {
        val (targetClass, key) = when {
            hookParam.packageName == SECURITY_CENTER &&
                (hookParam.processName == SECURITY_CENTER || hookParam.processName == "$SECURITY_CENTER.remote") ->
                NETWORK_ASSISTANT_MODEL to PlatformSettingsKeys.KEY_NETWORK_ASSISTANT_IGNORE_OFFLINE
            hookParam.packageName == CONTACTS &&
                (hookParam.processName == CONTACTS || hookParam.processName == "$CONTACTS:business") ->
                CONTACTS_BUSINESS_MODEL to PlatformSettingsKeys.KEY_CONTACTS_BUSINESS_HALL_IGNORE_OFFLINE
            else -> return
        }
        val apkPath = hookParam.appInfo?.sourceDir?.takeIf(String::isNotBlank) ?: run {
            DebugLog.hookSkipped(TAG, "carrier offline getter", "source APK unavailable")
            return
        }
        prepareDexTargets(
            name = "${CarrierOfflineFeature.id}:${hookParam.packageName}",
            resolve = { resolve(apkPath, targetClass) },
            install = { targets -> install(targets, key) },
        )
    }

    private fun resolve(apkPath: String, className: String): CarrierOfflineTargets? = DexRuntime.resolve(
        feature = CarrierOfflineFeature,
        apkPath = apkPath,
        cacheDir = dexCacheDirectory,
        compute = { session -> session.dexKit { bridge -> resolveGetter(bridge, className) } ?: emptyMap() },
        decode = { values, _ -> decodeTargets(values, className) },
    )

    private fun resolveGetter(bridge: DexKitBridge, className: String): Map<String, String> {
        val classes = bridge.findClass { matcher { className(className, StringMatchType.Equals) } }.toList()
        val classData = classes.singleOrNull() ?: run {
            DebugLog.w(TAG, "offline model candidates=${classes.size} class=$className")
            return emptyMap()
        }
        val getters = classData.methods.filter {
            it.isMethod && it.methodName == "isOffline" && it.paramCount == 0 && it.returnTypeName == "boolean"
        }.distinctBy { it.descriptor }
        if (getters.size != 1) {
            DebugLog.w(TAG, "offline getter candidates=${getters.size} class=$className")
            return emptyMap()
        }
        val getter = getters.single()
        val expectedField = "${classData.descriptor}->isOffline:Z"
        if (getter.usingFields.none { it.field.descriptor == expectedField }) {
            DebugLog.w(TAG, "offline getter does not read expected isOffline field class=$className")
            return emptyMap()
        }
        val callers = getter.callers.filter { it.isMethod }.distinctBy { it.descriptor }
        return buildMap {
            put("getter", getter.descriptor)
            put("caller_count", callers.size.toString())
            callers.forEachIndexed { index, caller -> put("caller.$index", caller.descriptor) }
        }
    }

    private fun decodeTargets(values: Map<String, String>, className: String): CarrierOfflineTargets? {
        val getterDescriptor = values["getter"] ?: return null
        val getter = MethodDescriptorCodec.method(getterDescriptor, classLoader) ?: return null
        if (getter.declaringClass.name != className || getter.name != "isOffline" ||
            getter.parameterCount != 0 || getter.returnType != Boolean::class.javaPrimitiveType ||
            getter.declaringClass.getDeclaredField("isOffline").type != Boolean::class.javaPrimitiveType
        ) {
            DebugLog.w(TAG, "cached offline getter owner/signature mismatch; rejecting cache")
            return null
        }
        val count = values["caller_count"]?.toIntOrNull()?.takeIf { it in 0..512 } ?: return null
        val callers = (0 until count).map { index ->
            val descriptor = values["caller.$index"] ?: return null
            MethodDescriptorCodec.method(descriptor, classLoader) ?: return null
        }
        return CarrierOfflineTargets(getter, callers)
    }

    private fun install(targets: CarrierOfflineTargets, key: String) {
        val method = targets.getter
        targets.callers.forEach { caller ->
            runCatching { deoptimize(caller) }
                .onFailure { DebugLog.w(TAG, "could not deoptimize offline getter caller ${caller.toGenericString()}", it) }
        }
        runCatching {
            method.isAccessible = true
            deoptimize(method)
            method.hook("carrier_offline_${MethodDescriptorCodec.descriptor(method)}") {
                before { param ->
                    HookFailurePolicy.open(TAG, "${method.name}.before", Unit) {
                        if (Preferences.getBoolean(key, false)) param.result = false
                    }
                }
            }
            DebugLog.i(TAG, "dynamic offline getter installed class=${method.declaringClass.name}")
        }.onFailure { DebugLog.hookFailed(TAG, method.toGenericString(), it) }
    }
}
