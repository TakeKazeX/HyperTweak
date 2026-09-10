package com.takekazex.hypertweak.hook.rules.guardprovider

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.util.ArrayList

/**
 * Stops GuardProvider's anti-fraud app-list upload at both native boundaries.
 *
 * `i82.a(ArrayList)` serializes the non-system installed-app list and returns
 * the server response. The current device keeps the endpoint literal in the
 * separate `xn1.d(GuardApplication, JSONObject)` method, so both methods are
 * resolved and validated. Returning null preserves GuardProvider's normal
 * empty/failed-response handling and never logs the list itself.
 */
object GuardProviderUploadAppListHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "GuardProviderUploadAppList"
    private const val PACKAGE = "com.miui.guardprovider"
    private const val ANTI_FRAUD_TAG = "AntiDefraudAppManager"
    private const val DETECT_ENDPOINT = "https://flash.sec.miui.com/detect/app"
    private const val GUARD_APPLICATION = "com.miui.guardprovider.GuardApplication"
    private const val JSON_OBJECT = "org.json.JSONObject"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!Preferences.getBoolean(Preferences.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST, false)) {
            DebugLog.hookSkipped(TAG, "GuardProvider app-list upload", "disabled")
            return
        }

        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, "GuardProvider app-list upload", "source APK unavailable")
            return
        }
        val resolved = DexKitManager.withBridge(apkPath) { bridge ->
            val referenceCandidates = bridge.findMethod {
                matcher {
                    returnType("java.lang.String")
                    addUsingString(ANTI_FRAUD_TAG, StringMatchType.Equals)
                    addUsingString(DETECT_ENDPOINT, StringMatchType.Equals)
                }
            }.toList()
            val collector = uniqueMethod(referenceCandidates) { method ->
                method.parameterTypes.contentEquals(arrayOf(ArrayList::class.java))
            } ?: run {
                // On the current GuardProvider APK the endpoint literal is in xn1.d(), while
                // i82.a() carries only the AntiDefraudAppManager error tag. Keep this fallback
                // shape-gated so a broad string match can never hook an unrelated String method.
                val collectorCandidates = bridge.findMethod {
                    matcher {
                        returnType("java.lang.String")
                        addUsingString(ANTI_FRAUD_TAG, StringMatchType.Equals)
                    }
                }.toList()
                uniqueMethod(collectorCandidates) { method ->
                    method.parameterTypes.contentEquals(arrayOf(ArrayList::class.java))
                }
            }

            val endpointCandidates = bridge.findMethod {
                matcher {
                    returnType("java.lang.String")
                    addUsingString(DETECT_ENDPOINT, StringMatchType.Equals)
                }
            }.toList()
            val endpoint = uniqueMethod(endpointCandidates) { method ->
                method.parameterTypes.size == 2 &&
                    method.parameterTypes[0].name == GUARD_APPLICATION &&
                    method.parameterTypes[1].name == JSON_OBJECT
            }
            ResolvedTargets(collector, endpoint)
        } ?: run {
            DebugLog.hookSkipped(TAG, "GuardProvider app-list upload", "DexKit unavailable")
            return
        }

        val methods = listOfNotNull(resolved.collector, resolved.endpoint)
            .distinctBy { it.toGenericString() }
        if (methods.isEmpty()) {
            DebugLog.hookSkipped(TAG, "GuardProvider app-list upload", "validated upload boundaries not found")
            return
        }

        var installed = 0
        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                deoptimize(method)
                method.hook {
                    before { param ->
                        HookFailurePolicy.open(TAG, "${method.name}.before", Unit) {
                            param.result = null
                        }
                    }
                }
                installed++
            }.onFailure { t ->
                DebugLog.hookFailed(TAG, method.toGenericString(), t)
            }
        }
        if (installed == 0) {
            DebugLog.hookSkipped(TAG, "GuardProvider app-list upload", "hook registration failed")
        } else {
            DebugLog.i(TAG, "installed-app upload blocked boundaries=$installed")
        }
    }

    private fun uniqueMethod(
        candidates: List<MethodData>,
        shape: (Method) -> Boolean
    ): Method? {
        val matching = candidates.mapNotNull { data ->
            runCatching { data.getMethodInstance(classLoader) }
                .onFailure { t -> DebugLog.w(TAG, "failed to inspect ${data.className}#${data.methodName}", t) }
                .getOrNull()
                ?.takeIf(shape)
                ?.takeIf { method -> method.returnType == String::class.java }
        }
        return matching.singleOrNull()
    }

    private data class ResolvedTargets(
        val collector: Method?,
        val endpoint: Method?
    )
}
