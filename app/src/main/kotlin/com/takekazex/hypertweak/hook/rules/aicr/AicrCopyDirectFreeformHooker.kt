package com.takekazex.hypertweak.hook.rules.aicr

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.util.logging.DebugLog
import org.json.JSONObject
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Carries the native Copy Direct card identity to the activity launch in system_server. */
internal object AicrCopyDirectFreeformHooker : StaticHooker() {
    override val hookerName = "AicrCopyDirectFreeform"

    const val COGNITION = "com.xiaomi.aicr:cognitionService"
    const val ACTION_PROVIDER = "com.xiaomi.aicr:actionprovider"

    override fun onHook() {
        val process = hookParam.processName
        if (process != COGNITION && process != ACTION_PROVIDER) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(
            name = hookerName,
            resolve = { resolve(apk, process == COGNITION) },
            install = { method ->
                deoptimize(method)
                if (process == COGNITION) hookRequest(method) else hookAction(method)
                DebugLog.i(hookerName, "Copy Direct launch routing ready process=$process")
            },
        )
    }

    private fun resolve(apk: String, cognition: Boolean): Method? {
        val feature = if (cognition) RequestFeature else ActionFeature
        fun matches(method: Method): Boolean {
            if (Modifier.isStatic(method.modifiers)) return false
            return if (cognition) {
                method.returnType == Any::class.java &&
                    method.parameterTypes.map { it.name } == listOf(
                        AicrDefaultBrowserCache.TOOL_REQUEST, "kotlin.coroutines.Continuation",
                    )
            } else {
                method.returnType == Bundle::class.java &&
                    method.parameterTypes.contentEquals(arrayOf(Context::class.java, Bundle::class.java))
            }
        }
        return DexRuntime.resolve(
            feature = feature, apkPath = apk, cacheDir = dexCacheDirectory, appInfo = hookParam.appInfo,
            compute = { session ->
                session.dexKit { bridge ->
                    val matches = bridge.findMethod(FindMethod().apply {
                        matcher {
                            if (cognition) {
                                paramTypes(AicrDefaultBrowserCache.TOOL_REQUEST, "kotlin.coroutines.Continuation")
                                addUsingString("ActionHandler", StringMatchType.Equals)
                                addUsingString("handle request: queryId=", StringMatchType.Equals)
                            } else {
                                paramTypes(Context::class.java, Bundle::class.java)
                                returnType(Bundle::class.java)
                                addUsingString("ActionProvider", StringMatchType.Equals)
                                addUsingString("execute action result, urnType=", StringMatchType.Equals)
                            }
                        }
                    }).mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
                        .filter(::matches)
                    matches.singleOrNull()?.let { mapOf("method" to MethodDescriptorCodec.descriptor(it)) }.orEmpty()
                } ?: emptyMap()
            },
            decode = { stored, _ ->
                stored["method"]?.let { MethodDescriptorCodec.method(it, classLoader) }?.takeIf(::matches)
            },
        )
    }

    private fun hookRequest(method: Method) {
        val access = requireNotNull(AicrToolRequestAccess.resolveHost(method.parameterTypes[0]))
        method.hook("aicr_copy_direct_freeform_request") {
            before { param ->
                if (!AicrCopyDirectFreeformSettings.anyEnabled()) return@before
                HookFailurePolicy.open(hookerName, "identify Copy Direct card", Unit) {
                    val request = param.args[0] ?: return@open
                    val replacement = access.rewrite(request, select = { query, tool, protocol ->
                        if (protocol != "action") null else AicrCopyDirectAction.fromRequest(query, tool)
                            ?.takeIf(AicrCopyDirectFreeformSettings::enabled)?.let { it to tool }
                    }) { (action, tool), raw -> action.markRequest(raw, tool) }
                    if (replacement != null) param.args[0] = replacement
                }
            }
        }
    }

    private fun hookAction(method: Method) {
        // ActionCoreProvider.call is the sole concrete caller on this build.
        val provider = Class.forName(PROVIDER, false, classLoader)
        val caller = provider.getDeclaredMethod("call", String::class.java, String::class.java, Bundle::class.java)
        check(caller.returnType == Bundle::class.java)
        deoptimize(caller)
        method.hook("aicr_copy_direct_freeform_action") {
            before { param ->
                HookFailurePolicy.open(hookerName, "apply Copy Direct card identity", Unit) {
                    val bundle = param.args[1] as? Bundle ?: return@open
                    val raw = bundle.getString("in") ?: return@open
                    if (!raw.contains(AicrCopyDirectAction.EXTRA)) return@open
                    val input = JSONObject(raw)
                    val action = AicrCopyDirectAction.fromMarker(input.remove(AicrCopyDirectAction.EXTRA) as? String)
                        ?: return@open
                    param.args[1] = Bundle(bundle).apply { putString("in", input.toString()) }
                    val context = param.args[0] as? Context ?: return@open
                    if (action.acceptsTool(bundle.getString("type")) && AicrCopyDirectFreeformSettings.enabled(action)) {
                        // The verified handlers call Context.startActivity synchronously. Delegate
                        // to the original context to preserve its attribution, options and fallback.
                        param.args[0] = LaunchContext(context, action)
                    }
                }
            }
        }
    }

    private class LaunchContext(base: Context, private val action: AicrCopyDirectAction) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) = super.startActivity(mark(intent))

        override fun startActivity(intent: Intent, options: Bundle?) = super.startActivity(mark(intent), options)

        private fun mark(intent: Intent): Intent = Intent(intent).putExtra(AicrCopyDirectAction.EXTRA, action.marker)
    }

    private const val PROVIDER =
        "com.xiaomi.aicr.hce_framework.protocol.action.com.xiaomi.aicr.actionprovider.ActionCoreProvider"

    private object RequestFeature : DexFeature {
        override val id = "aicr.copy_direct_freeform_request"
        override val schema = 1
    }

    private object ActionFeature : DexFeature {
        override val id = "aicr.copy_direct_freeform_action"
        override val schema = 1
    }
}
