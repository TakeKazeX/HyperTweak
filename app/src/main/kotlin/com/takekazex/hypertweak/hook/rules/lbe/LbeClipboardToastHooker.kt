package com.takekazex.hypertweak.hook.rules.lbe

import android.content.Context
import android.widget.Toast
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.ref.Reference

/**
 * Shows the clipboard-reading warning as a normal Android Toast.
 *
 * LBE's original `initToastView(String, int)` builds its own overlay with a close button for
 * `type == 1`. The standard Toast keeps the warning on the normal SystemUI Toast path, which also
 * lets the OS3/OS4 material hook style it. All target resolution is shape-checked and failures
 * leave the original LBE overlay untouched.
 */
object LbeClipboardToastHooker : StaticHooker() {
    private const val TAG = "LbeClipboardToast"
    private const val LBE_PACKAGE = "com.lbe.security.miui"
    private const val TOAST_UTIL = "com.lbe.security.utility.ToastUtil"
    private const val TOAST_METHOD = "initToastView"
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
            DebugLog.hookSkipped(TAG, TOAST_UTIL, "clipboard Toast disabled")
            return
        }

        val toastUtil = TOAST_UTIL.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, TOAST_UTIL, "class not found")
            return
        }
        contextField = findField(toastUtil, "mContext")
        if (contextField == null) {
            DebugLog.hookSkipped(TAG, "$TOAST_UTIL#mContext", "field not found")
            return
        }

        val method = CompatibleMethodResolver.find(
            toastUtil,
            TOAST_METHOD,
            parameterTypes = listOf(String::class.java, Int::class.javaPrimitiveType!!)
        ) ?: run {
            DebugLog.hookSkipped(TAG, "$TOAST_UTIL#$TOAST_METHOD(String,Int)", "method not found")
            return
        }

        runCatching {
            deoptimize(method)
            method.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, TOAST_METHOD, Unit) {
                        if (!enabled) return@open
                        if ((param.args.getOrNull(1) as? Number)?.toInt() != CLIPBOARD_TOAST_TYPE) {
                            return@open
                        }

                        if (showClipboardToast(param.thisObject, param.args.getOrNull(0) as? String)) {
                            // The return type is a View on the current LBE build. Assigning null
                            // also remains valid if a later build changes it to another reference
                            // type, and suppresses only this one original overlay.
                            param.result = null
                        }
                    }
                }
            }
            DebugLog.hookRegistered(TAG, "$TOAST_UTIL#$TOAST_METHOD(String,Int)")
        }.onFailure {
            DebugLog.hookFailed(TAG, "$TOAST_UTIL#$TOAST_METHOD(String,Int)", it)
        }
    }

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
        DebugLog.i(TAG, "replaced LBE clipboard overlay for package=$packageName")
        return true
    }

    private fun clipboardToastText(context: Context, label: String): CharSequence {
        val resourceId = context.resources.getIdentifier(
            TOAST_TEXT_RES_NAME,
            "string",
            LBE_PACKAGE
        )
        if (resourceId != 0) {
            runCatching { return context.getString(resourceId, label) }
                .onFailure { DebugLog.w(TAG, "failed to format $TOAST_TEXT_RES_NAME", it) }
            runCatching { return context.getString(resourceId) }
        }
        // A missing vendor resource should not make the clipboard event disappear altogether.
        return label
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()
            if (field != null) return field
            current = current.superclass
        }
        return null
    }
}
