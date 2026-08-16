package com.takekazex.hypertweak.hook.rules.ime

import android.view.inputmethod.InputMethodManager
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

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

    /** Resolved once at hook time; `getSupportIme` fires on every switcher list request. */
    private var sBottomViewHelperField: Field? = null
    private val mImmFieldCache = ConcurrentHashMap<Class<*>, Field?>()

    override fun onPrepareHotReload() {
        sBottomViewHelperField = null
        mImmFieldCache.clear()
    }

    override fun onHook() {
        val bottomManager = BOTTOM_MANAGER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BOTTOM_MANAGER, "class not found")
            return
        }

        sBottomViewHelperField = runCatching {
            bottomManager.getDeclaredField("sBottomViewHelper").apply { isAccessible = true }
        }.getOrNull()

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
            sBottomViewHelperField?.get(null)
        }.getOrNull() ?: return null
        val inputMethodManager = runCatching {
            mImmFieldOf(bottomViewHelper.javaClass)?.get(bottomViewHelper)
        }.getOrNull() as? InputMethodManager ?: return null
        return runCatching { inputMethodManager.enabledInputMethodList }.getOrNull()
    }

    private fun mImmFieldOf(helperClass: Class<*>): Field? {
        mImmFieldCache[helperClass]?.let { return it }
        val field = runCatching {
            helperClass.getDeclaredField("mImm").apply { isAccessible = true }
        }.getOrNull()
        mImmFieldCache[helperClass] = field
        return field
    }
}
