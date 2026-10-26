package com.takekazex.hypertweak.hook.rules.aicr

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.base.dex.DexRuntime

import android.content.Context
import android.content.ContentProvider
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.takekazex.hypertweak.hook.base.HostAccess
import androidx.core.net.toUri
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.InstallPolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import org.json.JSONObject
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/** Routes only HyperAI Copy Direct web actions to Android's current default browser. */
object AicrDefaultBrowserHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    private const val TAG = "AicrDefaultBrowser"
    private const val PACKAGE = "com.xiaomi.aicr"
    private const val XIAOMI_BROWSER = AicrCopyDirectBrowserPolicy.XIAOMI_BROWSER
    private const val COPY_DIRECT_TYPE_MARKER = "get_copy_direct_data"
    // Semantic identifiers, not obfuscated method names.
    private const val HOOK_LEGACY = "aicr_default_browser:legacy_copy_direct"
    private const val HOOK_ACTION_RESPONSE = "aicr_default_browser:copy_direct_response"
    private var titleRenderer: AicrBubbleTitleRenderer? = null
    private const val HOOK_BUBBLE_TITLE = "aicr_default_browser:render_browser_highlight"
    private const val HOOK_PANEL_DISPATCH = "aicr_default_browser:copy_direct_panel_dispatch"

    internal data class ResolvedTargets(
        val legacyJump: Method?,
        val actionProviderCall: Method?,
        val panelDispatch: Method?,
        val bubbleBind: Method? = null,
        val bubbleCallers: List<Method> = emptyList()
    )

    /** Prepare descriptors only while the user enables this feature. */
    override val installPolicy: InstallPolicy = InstallPolicy.WHEN_ENABLED

    override fun isFeatureEnabled(): Boolean = isEnabled()

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!isEnabled()) {
            DebugLog.hookSkippedDebug(TAG, "copy-direct default browser", "disabled")
            return
        }

        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, PACKAGE, "source APK unavailable for DexKit")
            return
        }
        prepareDexTargets(name = AicrDefaultBrowserFeature.id,
            resolve = { resolveTargets(apkPath) }, install = ::installTargets)
    }

    private fun installTargets(targets: ResolvedTargets) {
        targets.bubbleBind?.let { method ->
            targets.bubbleCallers.forEach(::deoptimize)
            hookBubbleTitle(method)
        }
            ?: DebugLog.hookSkipped(TAG, "native bubble title", "unique renderer contract not found")
        targets.legacyJump?.let(::hookLegacyJump)
            ?: DebugLog.hookSkipped(TAG, "Copy Direct legacy URL jump", "unique DexKit match not found")
        targets.actionProviderCall?.let(::hookCopyDirectResponse)
            ?: DebugLog.hookSkipped(TAG, "Copy Direct data response", "unique DexKit match not found")
        targets.panelDispatch?.let(::hookPanelDispatch)
            ?: DebugLog.hookSkipped(TAG, "Copy Direct AI Panel dispatch", "unique DexKit match not found")
    }

    /**
     * Cached descriptor resolution.
     *
     * This used to be an uncached `DexRuntime.query`, so all five AICR processes rescanned the same
     * APK on the main thread - 530-951 ms each, 3.1 s per boot (measured 2026-10-24).
     */
    private fun resolveTargets(apkPath: String): ResolvedTargets? = DexRuntime.resolve(
        feature = AicrDefaultBrowserFeature,
        apkPath = apkPath,
        cacheDir = dexCacheDirectory,
        cacheEmpty = true,
        appInfo = hookParam.appInfo,
        compute = { session -> AicrDefaultBrowserCache.descriptors(session, classLoader) },
        decode = { stored, _ -> AicrDefaultBrowserCache.decode(stored, classLoader) },
    )
    internal fun isLegacyJumpShape(method: Method): Boolean =
        method.parameterCount == 2 &&
            Context::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            method.parameterTypes[1] == String::class.java &&
            method.returnType == Void.TYPE

    internal fun isActionProviderShape(method: Method): Boolean =
        ContentProvider::class.java.isAssignableFrom(method.declaringClass) &&
            method.parameterTypes.contentEquals(
                arrayOf(String::class.java, String::class.java, Bundle::class.java)
            ) && method.returnType == Bundle::class.java

    internal fun isPanelDispatchShape(method: Method): Boolean =
        method.parameterTypes.map { it.name } == listOf(AicrDefaultBrowserCache.TOOL_REQUEST, "kotlin.coroutines.Continuation") &&
            method.returnType == Any::class.java &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

    internal fun isBubbleBindShape(method: Method): Boolean =
        method.returnType == Void.TYPE && method.parameterTypes.map { it.name } == listOf(AicrDefaultBrowserCache.CUE_DATA) &&
            ViewGroup::class.java.isAssignableFrom(method.declaringClass) &&
            method.declaringClass.methods.any { it.name == "getCueData" && it.parameterCount == 0 && it.returnType == method.parameterTypes[0] }

    private fun hookBubbleTitle(method: Method) {
        runCatching {
            val renderer = requireNotNull(AicrBubbleTitleRenderer.resolve(method)) { "native bubble title contract unavailable" }
            titleRenderer = renderer
            method.isAccessible = true
            deoptimize(method)
            method.hook(HOOK_BUBBLE_TITLE) {
                after { param ->
                    HookFailurePolicy.open(TAG, "native title highlight", Unit) {
                        if (!isEnabled()) return@open
                        val root = param.thisObject as? View ?: return@open
                        val cue = param.args.firstOrNull() ?: return@open
                        val browser = resolveDefaultBrowserPackage(root.context) ?: return@open
                        val label = applicationLabel(root.context, browser) ?: return@open
                        if (renderer.bind(root, cue, browser, label)) DebugLog.d(TAG, "applied native browser gradient target=$browser")
                    }
                }
            }
            refreshPresentation()
        }.onFailure { DebugLog.hookFailed(TAG, method.toGenericString(), it) }
    }

    /** Synchronous refresh participates in the existing settings acknowledgment and upgrade recovery. */
    fun refreshPresentation() = HostAccess.onMain {
        val renderer = titleRenderer ?: return@onMain
        if (!isEnabled()) { renderer.clear(); return@onMain }
        val context = currentContext() ?: return@onMain
        val browser = resolveDefaultBrowserPackage(context) ?: return@onMain
        val label = applicationLabel(context, browser) ?: return@onMain
        val type = Class.forName("android.view.WindowManagerGlobal")
        val global = HostAccess.requireDeclaredMethod(type, "getInstance", accessible = false).invoke(null)
        val roots = HostAccess.requireDeclaredField(type, "mViews", accessible = true).get(global) as? List<*> ?: return@onMain
        var applied = 0
        roots.toList().filterIsInstance<View>().forEach { root ->
            val params = root.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            if (params.title?.toString() == "com.xiaomi.aicr.bubble.collapsed") applied += renderer.recover(root, browser, label)
        }
        DebugLog.d(TAG, "refreshed native browser highlights roots=$applied")
    }

    override fun onPrepareHotReload() {
        HostAccess.onMain { titleRenderer?.clear(); titleRenderer = null }
    }
    override fun recoverHotReloadHosts() { refreshPresentation() }

    private fun hookLegacyJump(method: Method) {
        val target = method.toGenericString()
        runCatching {
            method.isAccessible = true
            deoptimize(method)
            method.hook(HOOK_LEGACY) {
                before { param ->
                    HookFailurePolicy.open(TAG, "legacy URL jump", Unit) {
                        if (!isEnabled()) return@open
                        val context = param.args.getOrNull(0) as? Context ?: return@open
                        val rawUrl = param.args.getOrNull(1) as? String ?: return@open
                        val uri = normalizedWebUri(rawUrl) ?: return@open
                        if (startWebIntent(context, buildWebIntent(uri, "clipboard_open"))) {
                            param.result = null
                        }
                    }
                }
            }
        }.onFailure { DebugLog.hookFailed(TAG, target, it) }
    }

    private fun hookCopyDirectResponse(method: Method) {
        val target = method.toGenericString()
        runCatching {
            method.isAccessible = true
            deoptimize(method)
            method.hook(HOOK_ACTION_RESPONSE) {
                after { param ->
                    HookFailurePolicy.open(TAG, "Copy Direct response", Unit) {
                        if (!isEnabled()) return@open
                        val request = param.args.getOrNull(2) as? Bundle ?: return@open
                        val response = param.result as? Bundle ?: return@open
                        val context = (param.thisObject as? ContentProvider)?.context ?: currentContext() ?: return@open
                        rewriteCopyDirectResponse(request, response, context)
                    }
                }
            }
        }.onFailure { DebugLog.hookFailed(TAG, target, it) }
    }

    private fun hookPanelDispatch(method: Method) {
        runCatching {
            // Resolve reflection once at installation, never on a clipboard/cue hot path.
            val access = requireNotNull(AicrToolRequestAccess.resolveHost(method.parameterTypes[0])) {
                "AI Panel ToolRequest/Json contract is missing or ambiguous"
            }
            method.isAccessible = true
            deoptimize(method)
            method.hook(HOOK_PANEL_DISPATCH) {
                before { param ->
                    HookFailurePolicy.open(TAG, "Copy Direct AI Panel dispatch", Unit) {
                        if (!isEnabled()) return@open
                        val request = param.args.getOrNull(0) ?: return@open
                        val context = currentContext() ?: return@open
                        val browser = resolveDefaultBrowserPackage(context) ?: return@open
                        if (browser == XIAOMI_BROWSER) return@open
                        val label = applicationLabel(context, browser) ?: return@open
                        val replacement = access.rewrite(request) { raw -> rewriteCueBatch(raw, context, browser, label) }
                        if (replacement != null) {
                            param.args[0] = replacement
                            DebugLog.d(TAG, "rewrote Copy Direct AI Panel dispatch target=$browser")
                        }
                    }
                }
            }
        }.onFailure { DebugLog.hookFailed(TAG, method.toGenericString(), it) }
    }

    private fun rewriteCueBatch(raw: String, context: Context, browser: String, label: String): String? =
        AicrCopyDirectCueBatch.rewrite(raw, browser, label,
            applicationLabel = { packageName -> applicationLabel(context, packageName) },
            rewriteWebIntent = { uri ->
                val intent = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
                val data = intent?.data
                if (intent == null || data == null || !isHttpUri(data) ||
                    (intent.action != null && intent.action != Intent.ACTION_VIEW) ||
                    !canHandleWebUri(context, browser, data)
                ) null else {
                    intent.component = null
                    intent.`package` = browser
                    intent.toUri(Intent.URI_INTENT_SCHEME)
                }
            }
        )?.also { DebugLog.d(TAG, "rewrote Copy Direct cue batch target=$browser") }

    private fun rewriteCopyDirectResponse(request: Bundle, response: Bundle, context: Context) {
        val requestType = request.getString("type") ?: return
        if (!requestType.contains(COPY_DIRECT_TYPE_MARKER)) return
        if (response.getInt("target_code", -1) != 0) return

        val targetOutText = response.getString("target_out") ?: return
        val targetOut = runCatching { JSONObject(targetOutText) }.getOrNull() ?: return
        if (targetOut.optInt("status", -1) != 0) return

        val copyDirectValue = targetOut.opt("copyDirectData")
        val copyDirect = when (copyDirectValue) {
            is String -> runCatching { JSONObject(copyDirectValue) }.getOrNull()
            is JSONObject -> copyDirectValue
            null -> null
            else -> null
        } ?: return
        val intentUri = copyDirect.optString("intentUri").takeIf(String::isNotBlank) ?: return
        val intent = runCatching { Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
        val data = intent.data ?: return
        if (!AicrCopyDirectBrowserPolicy.targetsXiaomiWebAction(
                packageName = intent.`package`,
                componentPackage = intent.component?.packageName,
                action = intent.action,
                scheme = data.scheme
            ) || !isHttpUri(data)
        ) return

        val defaultPackage = resolveDefaultBrowserPackage(context)
        if (defaultPackage == XIAOMI_BROWSER) return
        if (defaultPackage != null && !canHandleWebUri(context, defaultPackage, data)) return

        intent.component = null
        intent.`package` = defaultPackage
        copyDirect.put("intentUri", intent.toUri(Intent.URI_INTENT_SCHEME))

        // The native cue builder searches the title for its original app name to create
        // highlights. Replacing labels here makes that search fail and drops the color.
        // Rewrite text together with those ranges at the actual AI Panel dispatch boundary instead.

        if (copyDirectValue is String) {
            targetOut.put("copyDirectData", copyDirect.toString())
        } else {
            targetOut.put("copyDirectData", copyDirect)
        }
        response.putString("target_out", targetOut.toString())
        DebugLog.d(TAG, "rewrote Copy Direct response target=${defaultPackage ?: "system resolver"}")
    }

    private fun startWebIntent(context: Context, original: Intent): Boolean {
        val data = original.data ?: return false
        if (!isHttpUri(data)) return false
        val targetPackage = resolveDefaultBrowserPackage(context)
        if (targetPackage != null && !canHandleWebUri(context, targetPackage, data)) return false

        val replacement = Intent(original).apply {
            component = null
            `package` = targetPackage
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(replacement) }.isSuccess) return true
        if (targetPackage == null) return false

        val chooserFallback = Intent(replacement).apply { `package` = null }
        return runCatching { context.startActivity(chooserFallback) }.isSuccess
    }

    private fun buildWebIntent(uri: Uri, source: String): Intent =
        Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("open_source", source)
        }

    private fun normalizedWebUri(rawUrl: String): Uri? {
        return AicrCopyDirectBrowserPolicy.normalizeHttpUrl(rawUrl)?.toUri()?.takeIf(::isHttpUri)
    }

    private fun isHttpUri(uri: Uri): Boolean =
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()

    private fun resolveDefaultBrowserPackage(context: Context): String? {
        val probe = browserProbeIntent()
        val resolved = runCatching {
            context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()?.activityInfo ?: return null
        val packageName = resolved.packageName?.takeIf(String::isNotBlank) ?: return null
        if (packageName == "android" || packageName == "com.android.intentresolver" ||
            packageName == context.packageName || resolved.name.contains("ResolverActivity", ignoreCase = true)
        ) return null
        return packageName
    }

    private fun canHandleWebUri(context: Context, packageName: String, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            `package` = packageName
        }
        return runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName == packageName
        }.getOrDefault(false)
    }

    private fun browserProbeIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "https://example.invalid/".toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

    private fun applicationLabel(context: Context, packageName: String): String? = runCatching {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString().takeIf(String::isNotBlank)
    }.getOrNull()

    private fun currentContext(): Context? =
        hookParam.appContext ?: runCatching {
            io.github.lingqiqi5211.ezhooktool.xposed.EzXposed.appContextOrNull
        }.getOrNull()

    private fun isEnabled(): Boolean =
        Preferences.getBoolean(PlatformSettingsKeys.KEY_AICR_COPY_DIRECT_DEFAULT_BROWSER, false)
}

/** Persistent feature cache identity. */
internal object AicrDefaultBrowserFeature : com.takekazex.hypertweak.hook.base.dex.DexFeature {
    override val id = "aicr.copy_direct"
    override val schema = 5
}

/** Descriptor compute/decode for persistent target caching. */
internal object AicrDefaultBrowserCache {
    private const val LEGACY = "target.legacy"
    private const val ACTION = "target.action"
    private const val PANEL_DISPATCH = "target.panel_dispatch"
    private const val BUBBLE_BIND = "target.bubble_bind"
    const val CUE_DATA = "com.xiaomi.ai.bubble.core.model.CueData"

    const val LEGACY_URL_MARKER = "clipboard_open"
    const val ACTION_PROVIDER_MARKER = "ActionCoreProvider.call"
    const val PANEL_DISPATCH_MARKER = "AiPanelHandler"
    const val TOOL_REQUEST = "com.xiaomi.aicr.decisionframework.model.tool.ToolRequest"

    fun descriptors(session: com.takekazex.hypertweak.hook.base.dex.DexSession, loader: ClassLoader): Map<String, String> =
        session.dexKit { bridge ->
            fun single(query: org.luckypray.dexkit.query.FindMethod, valid: (java.lang.reflect.Method) -> Boolean): String? =
                bridge.findMethod(query).mapNotNull { data ->
                    runCatching { data.getMethodInstance(loader) }.getOrNull()
                }.filter(valid).singleOrNull()?.let {
                    com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec.descriptor(it)
                }
            buildMap {
                val binder = bridge.findMethod(org.luckypray.dexkit.query.FindMethod().apply { matcher {
                    paramTypes(CUE_DATA)
                    returnType(Void.TYPE)
                    addUsingString("schedule_create", StringMatchType.Equals)
                    addUsingString("document_extract", StringMatchType.Equals)
                } }).filter { runCatching { AicrDefaultBrowserHooker.isBubbleBindShape(it.getMethodInstance(loader)) }.getOrDefault(false) }
                    .singleOrNull()
                if (binder != null) {
                    put(BUBBLE_BIND, com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec.descriptor(binder.getMethodInstance(loader)))
                    binder.callers.mapNotNull { runCatching { it.getMethodInstance(loader) }.getOrNull() }.distinct()
                        .forEachIndexed { index, method ->
                            put("target.bubble_caller.$index", com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec.descriptor(method))
                        }
                }
                single(org.luckypray.dexkit.query.FindMethod().apply { matcher {
                    paramTypes(null, String::class.java)
                    returnType(Void.TYPE)
                    addUsingString(LEGACY_URL_MARKER, StringMatchType.Equals)
                } }, AicrDefaultBrowserHooker::isLegacyJumpShape)?.let { put(LEGACY, it) }
                single(org.luckypray.dexkit.query.FindMethod().apply { matcher {
                    paramTypes(String::class.java, String::class.java, Bundle::class.java)
                    returnType(Bundle::class.java)
                    addUsingString(ACTION_PROVIDER_MARKER, StringMatchType.Equals)
                } }, AicrDefaultBrowserHooker::isActionProviderShape)?.let { put(ACTION, it) }
                single(org.luckypray.dexkit.query.FindMethod().apply { matcher {
                    paramTypes(TOOL_REQUEST, "kotlin.coroutines.Continuation")
                    returnType(Any::class.java)
                    addUsingString(PANEL_DISPATCH_MARKER, StringMatchType.Equals)
                    addUsingString("EventEntity reported", StringMatchType.Equals)
                } }, AicrDefaultBrowserHooker::isPanelDispatchShape)?.let { put(PANEL_DISPATCH, it) }
            }
        } ?: emptyMap()

    fun decode(
        stored: Map<String, String>,
        loader: ClassLoader,
    ): AicrDefaultBrowserHooker.ResolvedTargets? {
        fun method(key: String): java.lang.reflect.Method? =
            stored[key]?.let { com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec.method(it, loader) }
        val legacy = method(LEGACY)
        val action = method(ACTION)
        val panelDispatch = method(PANEL_DISPATCH)
        val bubbleBind = method(BUBBLE_BIND)
        val bubbleCallers = stored.filterKeys { it.startsWith("target.bubble_caller.") }.values.map {
            com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec.method(it, loader) ?: return null
        }
        if (BUBBLE_BIND in stored && (bubbleBind == null || !AicrDefaultBrowserHooker.isBubbleBindShape(bubbleBind))) return null
        if (LEGACY in stored && (legacy == null || !AicrDefaultBrowserHooker.isLegacyJumpShape(legacy))) return null
        if (ACTION in stored && (action == null || !AicrDefaultBrowserHooker.isActionProviderShape(action))) return null
        if (PANEL_DISPATCH in stored && (panelDispatch == null || !AicrDefaultBrowserHooker.isPanelDispatchShape(panelDispatch))) return null
        // An entry that stored nothing usable must be a miss so the next run rescans.
        if (legacy == null && action == null && panelDispatch == null && bubbleBind == null) return null
        return AicrDefaultBrowserHooker.ResolvedTargets(legacy, action, panelDispatch, bubbleBind, bubbleCallers)
    }
}
