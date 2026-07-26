package com.takekazex.hypertweak.hook.rules.ime

import android.view.inputmethod.InputMethodManager
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Makes MIUI's keyboard switcher list every enabled input method instead of the customised ones.
 *
 * These classes live in the dex MIUI side-loads into an input method's process, so this hooker is
 * attached by [AospImeHooker] onto that ClassLoader rather than dispatched from `HookEntry`.
 *
 * None of this could be verified against a local artifact — `com.miui.phrase`, which supplies the
 * dex, is not in the reverse-engineering workspace. It therefore sits behind its own setting and
 * fails silently.
 */
object MiuiImeBottomHooker : StaticHooker() {
    private const val TAG = "MiuiImeBottom"

    private const val BOTTOM_MANAGER = "com.miui.inputmethod.InputMethodBottomManager"

    override val hotReloadMode = HotReloadMode.RECREATE

    override fun onHook() {
        val bottomManager = BOTTOM_MANAGER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BOTTOM_MANAGER, "class not found")
            return
        }

        val method = CompatibleMethodResolver.find(bottomManager, "getSupportIme") ?: run {
            DebugLog.hookSkipped(TAG, "$BOTTOM_MANAGER#getSupportIme()", "method not found")
            return
        }

        runCatching {
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "getSupportIme", Unit) {
                        enabledInputMethodList(bottomManager)?.let { param.result = it }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$BOTTOM_MANAGER#getSupportIme()", it)
        }
    }

    private fun enabledInputMethodList(bottomManager: Class<*>): Any? {
        val bottomViewHelper = runCatching {
            bottomManager.getDeclaredField("sBottomViewHelper").apply { isAccessible = true }.get(null)
        }.getOrNull() ?: return null
        val inputMethodManager = runCatching {
            bottomViewHelper.javaClass.getDeclaredField("mImm").apply { isAccessible = true }
                .get(bottomViewHelper)
        }.getOrNull() as? InputMethodManager ?: return null
        return runCatching { inputMethodManager.enabledInputMethodList }.getOrNull()
    }
}
