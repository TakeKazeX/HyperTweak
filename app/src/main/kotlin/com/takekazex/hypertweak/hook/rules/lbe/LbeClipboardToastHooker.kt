package com.takekazex.hypertweak.hook.rules.lbe

import android.content.Context
import android.view.View
import android.widget.Toast
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.ref.Reference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Shows the clipboard-reading warning as a normal Android Toast instead of the vendor overlay.
 *
 * Two privileged HyperOS packages ship the same `ToastUtil` overlay builder, and each runs it in its
 * own process: `com.lbe.security.miui` (AuthManager.apk) and `com.miui.securitycenter`
 * (MIUISecurityCenter.apk). On OS4.0.0.30.XPMCNXM the overlay the user sees for a clipboard read is
 * built by the **Security Center** copy, inside `com.miui.securitycenter.remote` — the LBE copy's
 * builder is never entered — so hooking only LBE leaves the overlay in place.
 *
 * Both copies expose the same two members, which is what this hooker keys on:
 * - a private `(String, int) -> View` builder that returns the overlay for `type == 1`
 *   (`TYPE_ACCESS_CLIP_NOTIFICATION`, the clipboard-read case) and null for every other type;
 * - a sibling `(String, int) -> void` entry point that null-checks the builder's result before
 *   touching it, so suppressing the overlay with `result = null` never reaches
 *   `WindowManager.addView(null)`.
 *
 * The LBE copy is unobfuscated and resolved by class name. The Security Center copy is obfuscated
 * per build (OS4.0.0.30.XPMCNXM: `l3.f`), so it is resolved structurally through DexKit: the vendor
 * debug line [TOAST_LOG_MARKER] identifies the class, and the entry point is then matched by
 * prototype. Nothing here throws out of the hook; a resolution miss leaves the stock overlay alone.
 */
object LbeClipboardToastHooker : StaticHooker() {
    private const val TAG = "LbeClipboardToast"
    private const val SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"

    /**
     * AuthManager.apk is still installed as `com.lbe.security.miui`, but on OS4.0.0.30.XPMCNXM its
     * classes live under `com.hyperos.security`. [TOAST_UTIL_LEGACY] is the namespace this hooker was
     * originally written against and matches no class in that APK, so it is retained only as a
     * fallback for older builds; [TOAST_UTIL] is the verified current target.
     */
    private const val TOAST_UTIL = "com.hyperos.security.utility.ToastUtil"
    private const val TOAST_UTIL_LEGACY = "com.lbe.security.utility.ToastUtil"
    private val TOAST_UTIL_CANDIDATES = listOf(TOAST_UTIL, TOAST_UTIL_LEGACY)

    /**
     * Debug line both vendor copies emit from the method that calls the overlay builder. Security
     * Center ships its copy obfuscated, so this string is what identifies the class there; the
     * prototype filter alone is the fallback when a build strips the debug logging.
     */
    private const val TOAST_LOG_MARKER = "showToastInternal  for "

    private const val CLIPBOARD_TOAST_TYPE = 1
    private const val TOAST_TEXT_RES_NAME = "overlay_read_clip_toast"

    @Volatile
    private var enabled = false

    private var contextField: Field? = null

    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    override fun onPrepareHotReload() {
        enabled = false
        contextField = null
    }

    override fun onHook() {
        enabled = Preferences.getBoolean(Preferences.KEY_LBE_CLIPBOARD_TOAST, false)
        if (!enabled) {
            DebugLog.hookSkipped(TAG, hookParam.packageName, "clipboard Toast disabled")
            return
        }

        val type = resolveOverlayType() ?: run {
            DebugLog.hookSkipped(TAG, hookParam.packageName, "overlay class not found")
            return
        }
        contextField = findContextField(type)
        if (contextField == null) {
            DebugLog.hookSkipped(TAG, "${type.name}#<Context>", "context field not found")
            return
        }
        val method = findOverlayMethod(type) ?: run {
            DebugLog.hookSkipped(TAG, "${type.name}#(String,int):View", "overlay builder not found")
            return
        }

        val target = "${type.name}#${method.name}(String,Int)"
        runCatching {
            deoptimize(method)
            method.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, method.name, Unit) {
                        if (!enabled) return@open
                        if ((param.args.getOrNull(1) as? Number)?.toInt() != CLIPBOARD_TOAST_TYPE) {
                            return@open
                        }

                        if (showClipboardToast(param.thisObject, param.args.getOrNull(0) as? String)) {
                            // Only this one original overlay is suppressed: the caller null-checks
                            // the result and returns before it builds the window.
                            param.result = null
                        }
                    }
                }
            }
            DebugLog.hookRegistered(TAG, target)
        }.onFailure {
            DebugLog.hookFailed(TAG, target, it)
        }
    }

    // ─── target resolution ───────────────────────────────────────────────────

    private fun resolveOverlayType(): Class<*>? = when (hookParam.packageName) {
        SECURITY_CENTER_PACKAGE -> resolveObfuscatedType()
        else -> TOAST_UTIL_CANDIDATES.firstNotNullOfOrNull { name ->
            name.toClassOrNull()?.takeIf(::looksLikeToastUtil)
        }
    }

    /**
     * Resolves Security Center's obfuscated copy. Candidates come from the vendor debug line first
     * and the entry-point prototype second; either way a candidate only counts when it still carries
     * the full ToastUtil shape, so a rename or a partial match cannot move the hook onto something
     * else.
     */
    private fun resolveObfuscatedType(): Class<*>? {
        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.w(TAG, "source APK unavailable; cannot resolve the obfuscated overlay class")
            return null
        }

        val candidates = DexKitManager.withBridge(apkPath) { bridge ->
            val marked = bridge.findMethod {
                matcher { addUsingString(TOAST_LOG_MARKER, StringMatchType.Equals) }
            }.map { it.className }
            val byPrototype = bridge.findMethod {
                matcher {
                    paramCount(2)
                    paramTypes(String::class.java, Int::class.javaPrimitiveType!!)
                    returnType(View::class.java)
                }
            }.map { it.className }
            (marked + byPrototype).distinct()
        }.orEmpty()

        if (candidates.isEmpty()) {
            DebugLog.w(TAG, "structural resolution produced no candidate class")
            return null
        }
        for (name in candidates) {
            val type = name.toClassOrNull() ?: continue
            if (looksLikeToastUtil(type)) return type
        }
        DebugLog.w(TAG, "structural resolution rejected ${candidates.size} candidate class(es)")
        return null
    }

    /** The private `(String, int) -> View` overlay builder; null for `type != 1` in both copies. */
    private fun findOverlayMethod(type: Class<*>): Method? = type.declaredMethods.firstOrNull { method ->
        method.parameterCount == 2 &&
            method.parameterTypes[0] == String::class.java &&
            method.parameterTypes[1] == Int::class.javaPrimitiveType &&
            method.returnType == View::class.java
    }?.apply { isAccessible = true }

    /**
     * Both copies pair the `(String, int) -> View` builder with a `(String, int) -> void` entry
     * point. Requiring both keeps the structural match anchored to the vendor shape rather than to a
     * bare prototype.
     */
    private fun looksLikeToastUtil(type: Class<*>): Boolean =
        findOverlayMethod(type) != null && type.declaredMethods.any { method ->
            method.parameterCount == 2 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                method.returnType == Void.TYPE
        }

    /**
     * The builder reads its `Context` back off its own instance, and the field is obfuscated in the
     * Security Center copy. Prefer the vendor's `mContext` name, and otherwise accept a
     * Context-typed field only when it is unambiguous.
     */
    private fun findContextField(type: Class<*>): Field? {
        val contextFields = mutableListOf<Field>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.filterTo(contextFields) { it.type == Context::class.java }
            current = current.superclass
        }
        val chosen = contextFields.firstOrNull { it.name == "mContext" } ?: contextFields.singleOrNull()
        return chosen?.apply { isAccessible = true }
    }

    // ─── replacement Toast ───────────────────────────────────────────────────

    private fun showClipboardToast(toastUtil: Any?, sourcePackage: String?): Boolean {
        val context = toastUtil?.let { instance ->
            runCatching {
                when (val value = contextField?.get(instance)) {
                    is Context -> value
                    is Reference<*> -> value.get() as? Context
                    else -> null
                }
            }.getOrNull()
        } ?: return false
        val packageName = sourcePackage?.takeIf { it.isNotBlank() } ?: return false

        val label = runCatching {
            val packageManager = context.packageManager
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName

        val text = clipboardToastText(context, label)
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        DebugLog.i(TAG, "replaced vendor clipboard overlay for package=$packageName")
        return true
    }

    private fun clipboardToastText(context: Context, label: String): CharSequence {
        // The warning text is the host's own resource: AuthManager and Security Center each ship
        // `overlay_read_clip_toast` with the same "%1$s accessed the clipboard" format.
        val resourceId = context.resources.getIdentifier(
            TOAST_TEXT_RES_NAME,
            "string",
            context.packageName
        )
        if (resourceId != 0) {
            runCatching { return context.getString(resourceId, label) }
                .onFailure { DebugLog.w(TAG, "failed to format $TOAST_TEXT_RES_NAME", it) }
            runCatching { return context.getString(resourceId) }
        }
        // A missing vendor resource should not make the clipboard event disappear altogether.
        return label
    }
}
