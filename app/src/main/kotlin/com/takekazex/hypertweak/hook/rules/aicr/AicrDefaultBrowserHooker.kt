package com.takekazex.hypertweak.hook.rules.aicr

import android.content.Context
import android.content.ContentProvider
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.json.JSONObject
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Routes only HyperAI Copy Direct web actions to Android's current default browser. */
object AicrDefaultBrowserHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    private const val TAG = "AicrDefaultBrowser"
    private const val PACKAGE = "com.xiaomi.aicr"
    private const val XIAOMI_BROWSER = AicrCopyDirectBrowserPolicy.XIAOMI_BROWSER
    private const val COPY_DIRECT_TYPE_MARKER = "get_copy_direct_data"
    private const val LEGACY_URL_MARKER = "clipboard_open"
    private const val ACTION_PROVIDER_MARKER = "ActionCoreProvider.call"
    private const val BUBBLE_RENDER_MARKER = "Display 或 BriefCard 为空"
    private const val CUE_DATA = "com.xiaomi.ai.bubble.core.model.CueData"

    // These IDs are semantic and stable across module releases and host obfuscation changes.
    private const val HOOK_LEGACY = "aicr_default_browser:legacy_copy_direct"
    private const val HOOK_ACTION_RESPONSE = "aicr_default_browser:copy_direct_response"
    private const val HOOK_BUBBLE_RENDER = "aicr_default_browser:copy_direct_bubble_render"
    private val BROWSER_LABEL_PATTERN = Regex("小米浏览器|Xiaomi Browser|Mi Browser", RegexOption.IGNORE_CASE)

    private data class LabelEdit(val start: Int, val end: Int, val replacementLength: Int)
    private data class LabelRewrite(val text: String, val edits: List<LabelEdit>)
    private data class HighlightRange(val target: Any, val start: Int, val end: Int)

    private data class ResolvedTargets(
        val legacyJump: Method?,
        val actionProviderCall: Method?,
        val bubbleRender: Method?
    )

    private data class FieldKey(val owner: Class<*>, val name: String)
    private val fields = ConcurrentHashMap<FieldKey, Field>()
    private val cueStringFields = ConcurrentHashMap<Class<*>, List<Field>>()

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return

        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, PACKAGE, "source APK unavailable for DexKit")
            return
        }
        val targets = resolveTargets(apkPath) ?: run {
            DebugLog.hookSkipped(TAG, PACKAGE, "DexKit bridge unavailable")
            return
        }

        targets.legacyJump?.let(::hookLegacyJump)
            ?: DebugLog.hookSkipped(TAG, "Copy Direct legacy URL jump", "unique DexKit match not found")
        targets.actionProviderCall?.let(::hookCopyDirectResponse)
            ?: DebugLog.hookSkipped(TAG, "Copy Direct data response", "unique DexKit match not found")
        targets.bubbleRender?.let(::hookBubbleRender)
            ?: DebugLog.hookSkipped(TAG, "Copy Direct bubble renderer", "unique DexKit match not found")
    }

    override fun onPrepareHotReload() {
        fields.clear()
        cueStringFields.clear()
    }

    private fun resolveTargets(apkPath: String): ResolvedTargets? =
        DexKitManager.withBridge(apkPath) { bridge ->
            val legacy = bridge.findMethod {
                matcher {
                    paramCount(2)
                    paramTypes(null, String::class.java)
                    returnType(Void.TYPE)
                    addUsingString(LEGACY_URL_MARKER, StringMatchType.Equals)
                }
            }.mapNotNull(::materialize)
                .filter(::isLegacyJumpShape)
                .singleOrNull()

            val actionProvider = bridge.findMethod {
                matcher {
                    paramTypes(String::class.java, String::class.java, Bundle::class.java)
                    returnType(Bundle::class.java)
                    addUsingString(ACTION_PROVIDER_MARKER, StringMatchType.Equals)
                }
            }.mapNotNull(::materialize)
                .filter(::isActionProviderShape)
                .singleOrNull()

            val bubble = bridge.findMethod {
                matcher {
                    paramTypes(CUE_DATA)
                    returnType(Void.TYPE)
                    addUsingString(BUBBLE_RENDER_MARKER, StringMatchType.Equals)
                }
            }.mapNotNull(::materialize)
                .filter(::isBubbleRenderShape)
                .singleOrNull()

            ResolvedTargets(legacy, actionProvider, bubble)
        }

    private fun materialize(data: MethodData): Method? =
        runCatching { data.getMethodInstance(classLoader) }
            .onFailure { DebugLog.w(TAG, "could not materialize DexKit target ${data.className}#${data.methodName}", it) }
            .getOrNull()

    private fun isLegacyJumpShape(method: Method): Boolean =
        method.parameterCount == 2 &&
            Context::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            method.parameterTypes[1] == String::class.java &&
            method.returnType == Void.TYPE

    private fun isActionProviderShape(method: Method): Boolean =
        ContentProvider::class.java.isAssignableFrom(method.declaringClass) &&
            method.parameterTypes.contentEquals(
                arrayOf(String::class.java, String::class.java, Bundle::class.java)
            ) && method.returnType == Bundle::class.java

    private fun isBubbleRenderShape(method: Method): Boolean =
        android.view.View::class.java.isAssignableFrom(method.declaringClass) &&
            method.parameterCount == 1 &&
            method.parameterTypes[0].name == CUE_DATA &&
            method.returnType == Void.TYPE

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

    private fun hookBubbleRender(method: Method) {
        val target = method.toGenericString()
        runCatching {
            method.isAccessible = true
            deoptimize(method)
            method.hook(HOOK_BUBBLE_RENDER) {
                before { param ->
                    HookFailurePolicy.open(TAG, "Copy Direct bubble metadata", Unit) {
                        if (!isEnabled()) return@open
                        val cueData = param.args.getOrNull(0) ?: return@open
                        val context = (param.thisObject as? android.view.View)?.context ?: currentContext() ?: return@open
                        rewriteBubbleMetadata(cueData, context)
                    }
                }
            }
        }.onFailure { DebugLog.hookFailed(TAG, target, it) }
    }

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

        val browserLabel = defaultPackage?.let { applicationLabel(context, it) ?: it }
        if (browserLabel != null) {
            rewriteLabel(copyDirect, "title", browserLabel)
            rewriteLabel(copyDirect, "description", browserLabel)
        }
        val iconUri = copyDirect.optString("iconUri")
        if (defaultPackage != null && iconUri.contains(XIAOMI_BROWSER) &&
            !iconUri.startsWith("android.resource://", ignoreCase = true)
        ) {
            copyDirect.put("iconUri", iconUri.replace(XIAOMI_BROWSER, defaultPackage))
        }

        if (copyDirectValue is String) {
            targetOut.put("copyDirectData", copyDirect.toString())
        } else {
            targetOut.put("copyDirectData", copyDirect)
        }
        response.putString("target_out", targetOut.toString())
        DebugLog.d(TAG, "rewrote Copy Direct response target=${defaultPackage ?: "system resolver"}")
    }

    private fun rewriteBubbleMetadata(cueData: Any, context: Context) {
        val actions = (readField(cueData, "actions") as? Iterable<*>)?.toList().orEmpty()
        val actionText = actions.joinToString(separator = "") { it?.toString().orEmpty() }
        val display = readField(cueData, "display") ?: return
        val currentPackage = readField(display, "targetPackage") as? String
        val briefCard = readField(display, "briefCard") ?: return
        val title = readField(briefCard, "title")
        val description = readField(briefCard, "description")
        val titleText = title?.let { readField(it, "text") as? String }.orEmpty()
        val descriptionText = description?.let { readField(it, "text") as? String }.orEmpty()
        val visibleBrowserLabel = containsXiaomiBrowserLabel(titleText) || containsXiaomiBrowserLabel(descriptionText)
        val icons = listOf("startIcon", "endIcon").mapNotNull { iconField ->
            iconField to readField(briefCard, iconField)
        }
        val iconValues = icons.mapNotNull { (_, icon) -> icon?.let { readField(it, "value") as? String } }
        val targetsXiaomiBrowser = currentPackage == XIAOMI_BROWSER ||
            actionText.contains(XIAOMI_BROWSER) || iconValues.any(::containsXiaomiBrowserPackage)
        val hasBrowserMetadata = targetsXiaomiBrowser || visibleBrowserLabel
        val hasCopyDirectMarker = directStringValues(cueData).any(::isCopyDirectMarker) ||
            isCopyDirectMarker(actionText)
        val hasWebAction = actionText.contains("http://", ignoreCase = true) ||
            actionText.contains("https://", ignoreCase = true) ||
            actionText.contains("scheme=http", ignoreCase = true) ||
            actionText.contains("scheme=https", ignoreCase = true)
        val hasIntentUriAction = actionText.contains("intentUri", ignoreCase = true) ||
            actionText.contains("resolve_intent", ignoreCase = true)
        val isCopyDirectBrowserCard = (hasCopyDirectMarker && hasBrowserMetadata) ||
            (targetsXiaomiBrowser && (visibleBrowserLabel || hasWebAction || hasIntentUriAction)) ||
            (visibleBrowserLabel && actions.isNotEmpty())
        if (!isCopyDirectBrowserCard) return

        val defaultPackage = resolveDefaultBrowserPackage(context) ?: return
        if (defaultPackage == XIAOMI_BROWSER) return
        val browserLabel = applicationLabel(context, defaultPackage) ?: defaultPackage

        writeField(display, "targetPackage", defaultPackage)
        rewriteCueText(title, browserLabel)
        rewriteCueText(description, browserLabel)
        icons.forEach { (iconField, icon) ->
            if (icon == null) return@forEach
            val value = readField(icon, "value") as? String ?: return@forEach
            val isDefaultBrowserIcon = containsXiaomiBrowserPackage(value) ||
                containsXiaomiBrowserLabel(value)
            if (isDefaultBrowserIcon || (iconField == "startIcon" && (visibleBrowserLabel || targetsXiaomiBrowser))) {
                writeField(icon, "type", "application")
                writeField(icon, "value", defaultPackage)
            }
        }
        DebugLog.d(TAG, "rewrote Copy Direct bubble metadata target=$defaultPackage")
    }

    private fun directStringValues(target: Any): List<String> = cueStringFields.computeIfAbsent(target.javaClass) { type ->
        type.declaredFields.filter { it.type == String::class.java && !it.isSynthetic }
            .onEach { field -> runCatching { field.isAccessible = true } }
    }.mapNotNull { field -> runCatching { field.get(target) as? String }.getOrNull() }

    private fun isCopyDirectMarker(value: String): Boolean =
        value.contains("copy_jump", ignoreCase = true) ||
            value.contains("copy_text_jump_app", ignoreCase = true) ||
            value.contains("copy_direct", ignoreCase = true) ||
            value.contains("get_copy_direct_data", ignoreCase = true) ||
            value.contains("clipboard_open", ignoreCase = true)

    private fun containsXiaomiBrowserPackage(value: String): Boolean =
        value == XIAOMI_BROWSER || value.contains(XIAOMI_BROWSER)

    private fun rewriteCueText(value: Any?, browserLabel: String) {
        if (value == null) return
        val current = readField(value, "text") as? String ?: return
        val rewrite = rewriteBrowserLabels(current, browserLabel)
        if (rewrite.text == current) return

        val highlights = (readField(value, "highlights") as? List<*>)
            ?.mapNotNull { highlight ->
                highlight ?: return@mapNotNull null
                val start = (readField(highlight, "start") as? Number)?.toInt() ?: return@mapNotNull null
                val end = (readField(highlight, "end") as? Number)?.toInt() ?: return@mapNotNull null
                HighlightRange(highlight, start, end)
            }.orEmpty()
        val normalizedHighlights = normalizeHighlightRanges(current, highlights)

        writeField(value, "text", rewrite.text)
        if ((readField(value, "text") as? String) != rewrite.text) return

        normalizedHighlights.forEach { highlight ->
            val start = mapHighlightOffset(highlight.start, rewrite.edits, isEnd = false)
                .coerceIn(0, rewrite.text.length)
            val end = mapHighlightOffset(highlight.end, rewrite.edits, isEnd = true)
                .coerceIn(start, rewrite.text.length)
            writeField(highlight.target, "start", start)
            writeField(highlight.target, "end", end)
        }
    }

    private fun rewriteLabel(json: JSONObject, key: String, browserLabel: String) {
        val current = json.optString(key).takeIf(String::isNotEmpty) ?: return
        val updated = replaceBrowserLabel(current, browserLabel)
        if (updated != current) json.put(key, updated)
    }

    private fun replaceBrowserLabel(value: String, browserLabel: String): String =
        rewriteBrowserLabels(value, browserLabel).text

    private fun rewriteBrowserLabels(value: String, browserLabel: String): LabelRewrite {
        val matches = BROWSER_LABEL_PATTERN.findAll(value).toList()
        if (matches.isEmpty()) return LabelRewrite(value, emptyList())

        val rewritten = StringBuilder(value.length)
        val edits = ArrayList<LabelEdit>(matches.size)
        var sourceIndex = 0
        matches.forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            rewritten.append(value, sourceIndex, start)
            rewritten.append(browserLabel)
            edits += LabelEdit(start, end, browserLabel.length)
            sourceIndex = end
        }
        rewritten.append(value, sourceIndex, value.length)
        return LabelRewrite(rewritten.toString(), edits)
    }

    /** AICR accepts both UTF-8 byte offsets and character offsets for gradient ranges. */
    private fun normalizeHighlightRanges(text: String, highlights: List<HighlightRange>): List<HighlightRange> {
        val byteLength = text.toByteArray(StandardCharsets.UTF_8).size
        val usesByteOffsets = highlights.any { it.end > text.length && it.end <= byteLength }
        if (!usesByteOffsets) {
            return highlights.map {
                it.copy(
                    start = it.start.coerceIn(0, text.length),
                    end = it.end.coerceIn(0, text.length)
                )
            }
        }

        val byteToCharacter = IntArray(byteLength + 1)
        var byteIndex = 0
        text.forEachIndexed { characterIndex, character ->
            val characterByteLength = character.toString().toByteArray(StandardCharsets.UTF_8).size
            repeat(characterByteLength) { offset ->
                val index = byteIndex + offset
                if (index < byteToCharacter.size) byteToCharacter[index] = characterIndex
            }
            byteIndex += characterByteLength
        }
        if (byteIndex < byteToCharacter.size) byteToCharacter[byteIndex] = text.length

        return highlights.map { highlight ->
            highlight.copy(
                start = byteOffsetToCharacter(highlight.start, byteToCharacter, text.length),
                end = byteOffsetToCharacter(highlight.end, byteToCharacter, text.length)
            )
        }
    }

    private fun byteOffsetToCharacter(offset: Int, byteToCharacter: IntArray, textLength: Int): Int =
        if (offset < 0 || offset >= byteToCharacter.size) textLength else byteToCharacter[offset]

    private fun mapHighlightOffset(offset: Int, edits: List<LabelEdit>, isEnd: Boolean): Int {
        var delta = 0
        edits.forEach { edit ->
            val newStart = edit.start + delta
            val newEnd = newStart + edit.replacementLength
            when {
                offset < edit.start -> return offset + delta
                offset == edit.start -> return newStart
                offset < edit.end -> return if (isEnd) newEnd else newStart
                offset == edit.end -> return newEnd
                else -> delta += edit.replacementLength - (edit.end - edit.start)
            }
        }
        return offset + delta
    }

    private fun containsXiaomiBrowserLabel(value: String): Boolean =
        value.contains("小米浏览器", ignoreCase = true) ||
            value.contains("Mi Browser", ignoreCase = true) ||
            value.contains("Xiaomi Browser", ignoreCase = true)

    private fun readField(target: Any, name: String): Any? = runCatching {
        val field = fields.computeIfAbsent(FieldKey(target.javaClass, name)) { key ->
            key.owner.getDeclaredField(key.name).apply { isAccessible = true }
        }
        field.get(target)
    }.getOrNull()

    private fun writeField(target: Any, name: String, value: Any?) {
        runCatching {
            val field = fields.computeIfAbsent(FieldKey(target.javaClass, name)) { key ->
                key.owner.getDeclaredField(key.name).apply { isAccessible = true }
            }
            field.set(target, value)
        }
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
        Preferences.getBoolean(Preferences.KEY_AICR_COPY_DIRECT_DEFAULT_BROWSER, false)
}
