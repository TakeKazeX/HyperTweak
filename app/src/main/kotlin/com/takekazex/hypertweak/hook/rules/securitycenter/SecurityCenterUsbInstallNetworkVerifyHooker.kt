package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.base.cachedDeclaredField
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.app.Activity
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.util.logging.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.lang.reflect.Field

private object UsbInstallVerifyFeature : DexFeature {
    override val id = "securitycenter.usb_install_network_verify"
    override val schema = 3
}

private const val TAG = "UsbInstallNetworkVerify"
private const val PACKAGE = "com.miui.securitycenter"
private const val ACTIVITY = "com.miui.permcenter.install.AdbInstallVerifyActivity"
private const val ACTIVITY_DESCRIPTOR = "Lcom/miui/permcenter/install/AdbInstallVerifyActivity;"
private const val INPUT_FLAG = "is_input"
private const val REQUEST_LOG = "start request for adb install!"
private const val RESULT_LOG = "server result: "
private const val INTERCEPT_PREFERENCE_KEY = "security_adb_install_enable"
private const val INTERCEPT_PROPERTY_KEY = "persist.security.adbinstall"
private const val SYSTEM_PROPERTIES_OWNER = "Lcom/miui/permcenter/compact/SystemPropertiesCompat;"

/** Production predicate shared with tests: input-mode verification always follows the host. */
internal object UsbInstallNetworkVerifyPolicy {
    fun shouldSkip(enabled: Boolean, isInput: Boolean): Boolean = enabled && !isInput
}

private data class UsbInstallVerifyTargets(
    val networkEntry: Method,
    val falseModeSetter: Method,
    val inputField: Field,
    val networkEntryCallers: List<Method>,
    val entryCallers: List<Method>,
    val evidenceMethods: List<Method>,
)

/** Skip only the online check before USB install; host account checks and input mode stay intact. */
object SecurityCenterUsbInstallNetworkVerifyHooker : DynamicHooker() {
    override fun onHook() {
        if (hookParam.packageName != PACKAGE || hookParam.processName != PACKAGE) return
        val apkPath = hookParam.appInfo?.sourceDir?.takeIf(String::isNotBlank) ?: run {
            DebugLog.hookSkipped(TAG, "USB install network verification", "source APK unavailable")
            return
        }
        prepareDexTargets(
            name = UsbInstallVerifyFeature.id,
            resolve = { resolve(apkPath) },
            install = { targets -> install(targets) },
        )
    }

    private fun resolve(apkPath: String): UsbInstallVerifyTargets? = DexRuntime.resolve(
        feature = UsbInstallVerifyFeature,
        apkPath = apkPath,
        cacheDir = dexCacheDirectory,
        compute = { session -> session.dexKit { bridge -> resolveDescriptors(bridge, apkPath) } ?: emptyMap() },
        decode = { values, _ -> decodeTargets(values) },
    )

    private fun resolveDescriptors(bridge: DexKitBridge, apkPath: String): Map<String, String> {
        val activityClass = bridge.findClass { matcher { className(ACTIVITY, StringMatchType.Equals) } }
            .toList().singleOrNull() ?: return missing("USB verification Activity class missing or ambiguous")
        val activityMethods = activityClass.methods.filter { it.isMethod }.distinctBy { it.descriptor }

        // Find the connectivity gate by platform calls and its asynchronous request behavior.
        val networkEntry = activityMethods.singleOrNull { method ->
            method.paramCount == 0 && method.returnTypeName == "void" &&
                "connectivity" in method.usingStrings &&
                method.invokes.any { it.descriptor == "Landroid/net/ConnectivityManager;->getActiveNetworkInfo()Landroid/net/NetworkInfo;" } &&
                method.invokes.any { it.descriptor == "Landroid/net/NetworkInfo;->isConnected()Z" } &&
                method.invokes.any { it.descriptor.startsWith("Landroid/os/AsyncTask;->executeOnExecutor(") }
        } ?: return missing("connectivity and AsyncTask gate missing or ambiguous")
        val networkCaller = networkEntry.callers.filter { it.isMethod }.distinctBy { it.descriptor }
            .singleOrNull { it.className == ACTIVITY && it.paramCount == 0 && it.returnTypeName == "void" }
            ?: return missing("Activity dialog entry for network gate missing or ambiguous")
        if (networkCaller.invokes.none { it.descriptor == networkEntry.descriptor }) {
            return missing("Activity entry no longer invokes connectivity gate")
        }
        val onCreate = networkCaller.callers.filter { it.isMethod }.distinctBy { it.descriptor }
            .singleOrNull { it.className == ACTIVITY && it.methodName == "onCreate" && it.paramTypeNames == listOf("android.os.Bundle") }
            ?: return missing("Activity entry caller chain to onCreate missing or ambiguous")
        val accountGetterCalls = onCreate.invokes.filter {
            it.paramTypeNames == listOf("android.content.Context") && it.returnTypeName == "java.lang.String"
        }.distinctBy { it.descriptor }
        val accountFailure = onCreate.invokes.filter {
            it.className == ACTIVITY && it.paramTypeNames == listOf("android.os.Bundle") && it.returnTypeName == "void"
        }.distinctBy { it.descriptor }
        if (INPUT_FLAG !in onCreate.usingStrings ||
            onCreate.invokes.none { it.descriptor == "Landroid/content/Intent;->getBooleanExtra(Ljava/lang/String;Z)Z" } ||
            onCreate.invokes.none { it.descriptor == "Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z" } ||
            accountGetterCalls.size != 1 || accountFailure.size != 1 ||
            onCreate.usingFields.none { it.usingType.isWrite() && it.field.declaredClassName == ACTIVITY && it.field.typeName == "boolean" }
        ) return missing("input flag or account qualification structure changed")

        val index = DexRuntime.operands(apkPath)
        val onCreateCode = index.code(onCreate.descriptor)
        val accountGetterDescriptor = accountGetterCalls.single().descriptor

        // Anchor the success callback to the observed network request, then resolve its Activity
        // success call by descriptors/signatures and field/branch data. Obfuscated names are unused.
        val requestMethods = bridge.findMethod {
            matcher {
                addUsingString(REQUEST_LOG, StringMatchType.Equals)
                returnType("java.lang.String")
            }
        }.filter {
            it.isMethod && it.returnTypeName == "java.lang.String" &&
                it.className.startsWith("$ACTIVITY\$") && REQUEST_LOG in it.usingStrings
        }.distinctBy { it.descriptor }
        val request = requestMethods.singleOrNull() ?: return missing("ADB network request with its request log missing or ambiguous")
        val requestClass = bridge.findClass { matcher { className(request.className, StringMatchType.Equals) } }
            .toList().singleOrNull() ?: return missing("request AsyncTask class missing or ambiguous")
        val callback = requestClass.methods.filter { it.isMethod }.distinctBy { it.descriptor }.singleOrNull { method ->
            method.paramTypeNames == listOf("java.lang.String") && method.returnTypeName == "void" &&
                RESULT_LOG in method.usingStrings && "AdbInstallActivity" in method.usingStrings &&
                method.opNames.any { it.startsWith("if-") } &&
                method.invokes.any { it.methodName == "finish" && it.paramCount == 0 }
        } ?: return missing("String response callback with null-success branch missing or ambiguous")
        val successCall = callback.invokes.filter {
            it.className == ACTIVITY && it.paramCount == 0 && it.returnTypeName == "void"
        }.distinctBy { it.descriptor }.singleOrNull()
            ?: return missing("response null-success Activity call missing or ambiguous")
        if (!UsbInstallDexContract.nullResponseCallsSuccess(index.code(callback.descriptor), successCall.descriptor)) {
            return missing("Activity success path is not exclusive to a null server result")
        }

        val success = bridge.getMethodData(successCall.descriptor)
            ?: return missing("response success Activity method could not be loaded")
        val inputFieldRead = success.usingFields.filter {
            it.usingType.isRead() && it.field.declaredClassName == ACTIVITY && it.field.typeName == "boolean"
        }.map { it.field.descriptor }.distinct().singleOrNull()
            ?: return missing("success path input boolean read missing or ambiguous")
        val inputFieldWritten = onCreate.usingFields.filter {
            it.usingType.isWrite() && it.field.declaredClassName == ACTIVITY && it.field.typeName == "boolean"
        }.map { it.field.descriptor }.distinct()
        if (inputFieldRead !in inputFieldWritten) return missing("onCreate does not write the success path input boolean")
        if (!UsbInstallDexContract.writesInputFlag(onCreateCode, inputFieldRead)) {
            return missing("onCreate does not write is_input into the success path field")
        }
        if (!UsbInstallDexContract.entryOnlyAfterAccount(
                onCreateCode, inputFieldRead, accountGetterDescriptor, networkCaller.descriptor
            )
        ) return missing("false-input network entry is not gated by successful account qualification")

        val successCode = index.code(success.descriptor)
        val modeCalls = UsbInstallDexContract.inputModeCalls(
            successCode, inputFieldRead, ACTIVITY_DESCRIPTOR, "Lcom/miui/permcenter/install/AdbInputApplyActivity;"
        ) ?: return missing("success path no longer has unique true/false input-mode setters")
        val falseCall = modeCalls.falseInput

        // Confirm the false-input wrapper reaches the preference/property setter via its actual
        // key operands; the obfuscated bridge method name is deliberately not part of the contract.
        val falseSetterDescriptor = DexOperandIndex.descriptor(falseCall.method)
        val falseSetterCode = index.code(falseSetterDescriptor)
        val propertySetter = falseSetterCode.calls.filter { site ->
            site.method.parameterTypes.map(Any::toString) == listOf("Z") && site.method.returnType == "V" &&
                site.arguments.lastOrNull() == DexOperandIndex.Value.Argument(0)
        }.mapNotNull { site -> bridge.getMethodData(DexOperandIndex.descriptor(site.method)) }
            .filter { helper -> hasInterceptSetterOperands(helper, index) }
            .distinctBy { it.descriptor }.singleOrNull()
            ?: return missing("false-input wrapper does not uniquely reach the persisted USB-enable setter")

        val networkCallers = networkEntry.callers.filter { it.isMethod }.distinctBy { it.descriptor }
        val entryCallers = networkCaller.callers.filter { it.isMethod }.distinctBy { it.descriptor }
        if (onCreate.descriptor !in entryCallers.map { it.descriptor }) return missing("onCreate is not a caller of the account-qualified entry")

        return buildMap {
            put("network", networkEntry.descriptor)
            put("false_setter", falseSetterDescriptor)
            put("input_field", inputFieldRead)
            put("success", success.descriptor)
            put("request", request.descriptor)
            put("response_callback", callback.descriptor)
            put("persisted_setter", propertySetter.descriptor)
            putDescriptors("network_caller", networkCallers.map { it.descriptor })
            putDescriptors("entry_caller", entryCallers.map { it.descriptor })
        }
    }

    private fun hasInterceptSetterOperands(method: MethodData, index: DexOperandIndex): Boolean {
        if (INTERCEPT_PREFERENCE_KEY !in method.usingStrings || INTERCEPT_PROPERTY_KEY !in method.usingStrings) return false
        val code = index.code(method.descriptor)
        val preferenceWrites = code.calls.filter { call ->
            call.method.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;", "Z") &&
                call.method.returnType == "V" &&
                call.arguments == listOf(DexOperandIndex.Value.Text(INTERCEPT_PREFERENCE_KEY), DexOperandIndex.Value.Argument(0)) &&
                index.definition(call.method)?.accessFlags?.and(0x8) == 0x8
        }
        val propertyWrites = code.calls.filter { call ->
            call.method.definingClass == SYSTEM_PROPERTIES_OWNER && call.method.name == "set" &&
                call.method.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;", "Ljava/lang/String;") &&
                call.method.returnType == "I" &&
                call.arguments.firstOrNull() == DexOperandIndex.Value.Text(INTERCEPT_PROPERTY_KEY)
        }
        return preferenceWrites.size == 1 && propertyWrites.size == 1
    }

    private fun MutableMap<String, String>.putDescriptors(prefix: String, descriptors: List<String>) {
        put("$prefix.count", descriptors.size.toString())
        descriptors.forEachIndexed { index, descriptor -> put("$prefix.$index", descriptor) }
    }

    private fun missing(reason: String): Map<String, String> {
        DebugLog.w(TAG, "$reason; leaving native verification unchanged")
        return emptyMap()
    }

    private fun decodeTargets(values: Map<String, String>): UsbInstallVerifyTargets? {
        fun method(key: String): Method? = values[key]?.let { MethodDescriptorCodec.method(it, classLoader) }
        fun descriptorList(prefix: String): List<Method>? {
            val count = values["$prefix.count"]?.toIntOrNull()?.takeIf { it in 0..64 } ?: return null
            return (0 until count).map { index ->
                val descriptor = values["$prefix.$index"] ?: return null
                MethodDescriptorCodec.method(descriptor, classLoader) ?: return null
            }
        }
        val network = method("network") ?: return null
        val falseSetter = method("false_setter") ?: return null
        val evidence = listOf("success", "request", "response_callback", "persisted_setter").map { key ->
            method(key) ?: return null
        }
        val fieldDescriptor = values["input_field"] ?: return null
        val expectedFieldPrefix = "${ACTIVITY_DESCRIPTOR}->"
        if (!fieldDescriptor.startsWith(expectedFieldPrefix) || !fieldDescriptor.endsWith(":Z")) return null
        val fieldName = fieldDescriptor.removePrefix(expectedFieldPrefix).removeSuffix(":Z")
        val inputField = runCatching { classLoader.loadClass(ACTIVITY).cachedDeclaredField(fieldName, accessible = true) }.getOrNull()
            ?: return null
        if (inputField.type != Boolean::class.javaPrimitiveType) return null
        if (network.declaringClass.name != ACTIVITY || network.parameterCount != 0 || network.returnType != Void.TYPE ||
            falseSetter.declaringClass.name != ACTIVITY || falseSetter.parameterCount != 1 ||
            falseSetter.parameterTypes[0] != Boolean::class.javaPrimitiveType || falseSetter.returnType != Void.TYPE
        ) return null
        return UsbInstallVerifyTargets(
            networkEntry = network,
            falseModeSetter = falseSetter,
            inputField = inputField,
            networkEntryCallers = descriptorList("network_caller") ?: return null,
            entryCallers = descriptorList("entry_caller") ?: return null,
            evidenceMethods = evidence,
        )
    }

    private fun install(targets: UsbInstallVerifyTargets) {
        (targets.networkEntryCallers + targets.entryCallers)
            .distinctBy(Method::toGenericString).forEach { caller ->
                runCatching { deoptimize(caller) }
                    .onFailure { DebugLog.w(TAG, "could not deoptimize USB verification caller", it) }
            }
        val network = targets.networkEntry
        runCatching {
            network.isAccessible = true
            deoptimize(network)
            network.hook("usb_install_network_verify_${MethodDescriptorCodec.descriptor(network)}") {
                before { param ->
                    HookFailurePolicy.open(TAG, "skip online check", Unit) {
                        val activity = param.thisObject as? Activity ?: return@open
                        if (!Preferences.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_BYPASS_USB_INSTALL_NETWORK_VERIFY, false)) return@open
                        val isInput = runCatching { targets.inputField.getBoolean(activity) }.getOrElse { error ->
                            DebugLog.w(TAG, "could not read resolved USB input-mode field", error)
                            return@open
                        }
                        if (!UsbInstallNetworkVerifyPolicy.shouldSkip(true, isInput)) return@open
                        // This gate is reached from the host entry only after its account check.
                        // Invoke the structural false-input success wrapper, then finish as the
                        // host's successful network callback does. This does not roll back later.
                        try {
                            targets.falseModeSetter.isAccessible = true
                            targets.falseModeSetter.invoke(activity, true)
                        } catch (error: Throwable) {
                            DebugLog.hookFailed(TAG, "invoke native false-input setter", error)
                            return@open
                        }
                        param.result = null
                        runCatching { activity.finish() }
                    }
                }
            }
            DebugLog.i(TAG, "USB online verification gate installed; input=true and account qualification remain host-owned")
        }.onFailure { DebugLog.hookFailed(TAG, network.toGenericString(), it) }
    }
}
