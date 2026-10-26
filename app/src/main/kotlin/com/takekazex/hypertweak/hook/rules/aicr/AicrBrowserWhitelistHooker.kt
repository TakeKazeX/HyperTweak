package com.takekazex.hypertweak.hook.rules.aicr

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import org.luckypray.dexkit.query.enums.StringMatchType

/** Extends only the native Open URL app allowlist; its checkbox listener still owns persistence. */
object AicrBrowserWhitelistHooker : StaticHooker() {
    private const val KEY = "settings.copy_text_jump_app.jump_url"
    private const val MARKER = "renderAppList: shown="
    private val models = ConcurrentHashMap<Class<*>, java.lang.reflect.Constructor<*>>()
    private val preferenceMethods = ConcurrentHashMap<Class<*>, Method>()
    private data class Targets(val render: Method, val model: Class<*>)
    private object Feature : DexFeature { override val id = "aicr.browser_whitelist"; override val schema = 1 }

    override fun onHook() {
        if (!isMainProcess) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(name = Feature.id, resolve = {
            DexRuntime.resolve(feature = Feature, apkPath = apk, cacheDir = dexCacheDirectory,
                appInfo = hookParam.appInfo, cacheEmpty = true,
                compute = { session -> session.dexKit { bridge ->
                    val render = bridge.findMethod { matcher {
                        returnType(Void.TYPE)
                        addUsingString(MARKER, StringMatchType.Equals)
                    } }.filter { runCatching { valid(it.getMethodInstance(classLoader)) }.getOrDefault(false) }.singleOrNull()
                    val model = render?.invokes?.filter { it.paramCount == 0 && it.returnTypeName == "java.lang.String" }
                        ?.mapNotNull { runCatching { it.getMethodInstance(classLoader).declaringClass }.getOrNull() }
                        ?.distinct()?.filter(::validModel)?.singleOrNull()
                    if (render != null && model != null) mapOf(
                        "render" to MethodDescriptorCodec.descriptor(render.getMethodInstance(classLoader)), "model" to model.name)
                    else emptyMap()
                } ?: emptyMap() },
                decode = { stored, _ ->
                    val render = stored["render"]?.let { MethodDescriptorCodec.method(it, classLoader) }?.takeIf(::valid)
                    val model = stored["model"]?.let { Class.forName(it, false, classLoader) }?.takeIf(::validModel)
                    if (render != null && model != null) Targets(render, model) else null
                })
        }, install = ::install)
    }

    private fun valid(method: Method): Boolean = Modifier.isStatic(method.modifiers) &&
        method.returnType == Void.TYPE && method.parameterCount == 2 && method.parameterTypes[1] == List::class.java &&
        method.parameterTypes[0].methods.any { it.name == "getArguments" && it.returnType == Bundle::class.java }

    private fun validModel(type: Class<*>): Boolean = type.declaredFields.count {
        it.name in setOf("packageName", "name") && it.type == String::class.java
    } == 2 && type.declaredConstructors.count {
        it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
    } == 1

    private fun install(targets: Targets) {
        val method = targets.render
        val fragment = method.parameterTypes[0]
        val arguments = fragment.getMethod("getArguments")
        val requireContext = fragment.getMethod("requireContext")
        val rows = fragment.declaredFields.single { it.name == "appPrefByKey" && Map::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
        // Include the same packages before native batch state loading. Otherwise added rows
        // would appear enabled again after reopening even if their native switch was saved off.
        val vm = fragment.declaredMethods.single { getter ->
            getter.parameterCount == 0 && getter.returnType.declaredFields.any { it.name == "_appSwitchStates" } &&
                getter.returnType.declaredFields.any { it.name == "_supportedApps" }
        }.returnType
        val loadStates = vm.declaredMethods.single { candidate ->
            candidate.returnType == Any::class.java && candidate.parameterCount == 3 &&
                candidate.parameterTypes[0] == String::class.java && candidate.parameterTypes[1] == List::class.java &&
                candidate.parameterTypes[2].name == "kotlin.coroutines.jvm.internal.ContinuationImpl"
        }.apply { isAccessible = true }
        val contextField = vm.declaredFields.single { Context::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
        deoptimize(loadStates)
        loadStates.hook("aicr_browser_whitelist:load_native_states") {
            before { param ->
                HookFailurePolicy.open("AicrBrowserWhitelist", "browser switch batch", Unit) {
                    if (param.args.getOrNull(0) != KEY) return@open
                    val context = contextField.get(param.thisObject) as? Context ?: return@open
                    val original = param.args.getOrNull(1) as? List<*> ?: return@open
                    param.args[1] = appendBrowsers(original, context, targets.model)
                }
            }
        }
        method.isAccessible = true
        deoptimize(method)
        method.hook("aicr_browser_whitelist:render_open_url") {
            before { param ->
                HookFailurePolicy.open("AicrBrowserWhitelist", "native browser list", Unit) {
                    val host = param.args.getOrNull(0) ?: return@open
                    if ((arguments.invoke(host) as? Bundle)?.getString("extra_key") != KEY) return@open
                    val context = requireContext.invoke(host) as? Context ?: return@open
                    val original = param.args.getOrNull(1) as? List<*> ?: return@open
                    param.args[1] = appendBrowsers(original, context, targets.model)
                }
            }
            after { param ->
                HookFailurePolicy.open("AicrBrowserWhitelist", "native browser row state", Unit) {
                    val host = param.args.getOrNull(0) ?: return@open
                    if ((arguments.invoke(host) as? Bundle)?.getString("extra_key") != KEY) return@open
                    val context = requireContext.invoke(host) as? Context ?: return@open
                    val preferences = rows.get(host) as? Map<*, *> ?: return@open
                    val browsers = AicrBrowserCatalog.installed(context)
                    browsers.forEach { browser ->
                        val preference = preferences["$KEY#${browser.packageName}"] ?: return@forEach
                        val methods = preferenceMethods.computeIfAbsent(preference.javaClass) { type ->
                            val base = generateSequence(type) { it.superclass }.single { it.name == "androidx.preference.Preference" }
                            val icon = base.declaredMethods.single {
                                it.parameterTypes.contentEquals(arrayOf(Drawable::class.java)) && it.returnType == Void.TYPE
                            }.apply { isAccessible = true }
                            icon
                        }
                        methods.invoke(preference, context.packageManager.getApplicationIcon(browser.packageName))
                    }
                    DebugLog.d("AicrBrowserWhitelist", "rendered native browser rows=${browsers.size}")
                }
            }
        }
    }
    private fun appendBrowsers(original: List<*>, context: Context, type: Class<*>): List<*> {
        val ctor = models.computeIfAbsent(type) {
            it.declaredConstructors.single { candidate ->
                candidate.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
            }.apply { isAccessible = true }
        }
        val packageField = type.getDeclaredField("packageName").apply { isAccessible = true }
        val existing = original.mapNotNull { it?.let { item -> packageField.get(item) as? String } }.toSet()
        return original + AicrBrowserCatalog.installed(context).filter { it.packageName !in existing }
            .map { ctor.newInstance(it.packageName, it.label) }
    }

}
