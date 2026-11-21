package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.InstallPolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex.Value
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.OtaNotificationReplay
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.lang.reflect.Method

internal object HtmlViewerMilletHooker : StaticHooker() {
    private const val TAG = "HtmlViewerMillet"
    private const val JAR = "/system_ext/framework/miui-services.jar"
    private const val EXECUTOR = "Lcom/miui/server/greeze/power/ActionExecute;"
    private const val POLICY = "Lcom/miui/server/greeze/power/PolicyMaker;"
    private val enabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_HTML_VIEWER_MILLET, false)
    }
    override val installPolicy = InstallPolicy.WHEN_ENABLED
    override fun isFeatureEnabled() = enabled

    private val decision = ThreadLocal<Any>()
    private data class Target(val gate: Method, val decision: Method, val callers: List<Method>)

    override fun onHook() {
        if (!PlatformLevel.isOs4) return
        prepareDexTargets(name = TAG, resolve = ::resolve) { target ->
            installDexContract {
                (target.callers + target.decision + target.gate).forEach {
                    check(deoptimize(it)) { "Cannot deoptimize Millet policy method: $it" }
                }
                target.gate.hook("html_viewer_millet_system_app_gate") {
                    before { param ->
                        if (!generationRetired && enabled && decision.get() === param.thisObject &&
                            param.args[0] == OtaNotificationReplay.VIEWER
                        ) param.result = false
                    }
                }
                target.decision.hook("html_viewer_millet_policy_context") {
                    before { param ->
                        if (!generationRetired && enabled && param.args[1] == OtaNotificationReplay.VIEWER) {
                            decision.set(param.thisObject)
                        }
                    }
                    after { decision.remove() }
                }
            }
            DebugLog.hookRegistered(TAG, "HTML Viewer system-app check in Millet final decision")
        }
    }

    private fun resolve(): Target? {
        val index = DexRuntime.operands(JAR)
        val candidates = index.declared(POLICY).filter { it.parameterTypes.map(CharSequence::toString) ==
            listOf("I", "Ljava/lang/String;") && it.returnType == "I" && index.code(it).hasString("systemapp") }
        val policy = candidates.singleOrNull() ?: return null
        val code = index.code(policy)
        val gate = code.calls.singleOrNull { call ->
            call.method.definingClass == POLICY && call.method.returnType == "Z" &&
                call.method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
                call.arguments.lastOrNull() == Value.Argument(1) &&
                index.code(call.method).calls.any { it.method.name == "isSystempartionApp" }
        }?.method ?: return null
        if (code.calls.none { call ->
                call.method.name == "contains" && call.arguments.lastOrNull() == Value.Argument(1) &&
                    (call.arguments.firstOrNull() as? Value.Field)?.field?.name == "MIUI_APP_CTRL_LIST"
            }) return null
        val allowFreeze = index.declared(POLICY).singleOrNull { candidate ->
            index.code(candidate).calls.any {
                MethodDescriptorCodec.descriptor(it.method) == MethodDescriptorCodec.descriptor(policy)
            }
        } ?: return null
        val executor = index.declared(EXECUTOR).singleOrNull { candidate ->
            val calls = index.code(candidate).calls
            calls.any { MethodDescriptorCodec.descriptor(it.method) == MethodDescriptorCodec.descriptor(allowFreeze) } &&
                calls.any { it.method.definingClass == EXECUTOR && it.method.name == "freezeUid" }
        } ?: return null
        return Target(
            index.method(gate, classLoader) ?: return null,
            index.method(policy, classLoader) ?: return null,
            listOf(index.method(allowFreeze, classLoader) ?: return null, index.method(executor, classLoader) ?: return null),
        )
    }
}
