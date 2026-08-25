package com.takekazex.hypertweak.hook.rules.ime

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes MIUI's keyboard switcher list every enabled input method instead of the customised ones,
 * and keeps MIUI's own bottom view from stacking under the AOSP caption bar.
 *
 * These classes live in the dex MIUI side-loads into an input method's process, so this hooker is
 * attached by [AospImeHooker] onto that ClassLoader rather than dispatched from `HookEntry`.
 *
 * Verified against the OS4.0.0.19 `com.miui.phrase` APK (MIUIFrequentPhrase): the switcher filter
 * is `getSupportIme()` pruning the enabled list by the `sImeMinVersionSupport` allowlist (six
 * MIUI-customized packages); the popup (`InputMethodSwitchPopupView`) does no further filtering.
 * `addMiuiBottomView` is called unconditionally from `InputMethodService.onCreate` on .19, so it
 * must be neutralized when the AOSP bar is the active bar or the two bars stack.
 */
object MiuiImeBottomHooker : StaticHooker() {
    private const val TAG = "MiuiImeBottom"

    private const val BOTTOM_MANAGER = "com.miui.inputmethod.InputMethodBottomManager"
    private const val INPUT_METHOD_SERVICE = "android.inputmethodservice.InputMethodService"

    override val hotReloadMode = HotReloadMode.RECREATE

    /** Resolved once at hook time; `getSupportIme` fires on every switcher list request. */
    private var sBottomViewHelperField: Field? = null
    private val mImmFieldCache = ConcurrentHashMap<Class<*>, Field?>()

    /** Process-wide `InputMethodManager` fallback when the bottom-view helper is not ready. */
    private var immSingleton: InputMethodManager? = null
    private var immSingletonResolved = false

    override fun onPrepareHotReload() {
        sBottomViewHelperField = null
        mImmFieldCache.clear()
        immSingleton = null
        immSingletonResolved = false
    }

    override fun onHook() {
        val bottomManager = BOTTOM_MANAGER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BOTTOM_MANAGER, "class not found")
            return
        }

        sBottomViewHelperField = runCatching {
            bottomManager.getDeclaredField("sBottomViewHelper").apply { isAccessible = true }
        }.getOrNull()

        // The dex's allowlist is the authoritative "MIUI-customized keyboard" signal on .19,
        // where InputMethodServiceInjector.isImeSupport(Context) no longer exists.
        runCatching {
            val map = bottomManager.getDeclaredField("sImeMinVersionSupport").apply { isAccessible = true }
                .get(null) as? Map<*, *>
            if (map != null) {
                MiuiCustomizedImePackages.update(map.keys.filterIsInstance<String>().toSet())
            }
        }.onFailure { DebugLog.d(TAG, "sImeMinVersionSupport not readable: $it") }

        hookGetSupportIme(bottomManager)
        hookAddMiuiBottomView(bottomManager)
        hookDeleteNotSupportIme(bottomManager)
    }

    /**
     * The switcher popup is built from `getSupportIme()`; the original prunes the enabled list by
     * `sImeMinVersionSupport`, so replace the result with the full enabled list when the
     * "list all keyboards" setting is on. The method is small enough to be AOT-inlined, so it is
     * deoptimized first.
     */
    private fun hookGetSupportIme(bottomManager: Class<*>) {
        val method = CompatibleMethodResolver.find(bottomManager, "getSupportIme") ?: run {
            DebugLog.hookSkipped(TAG, "$BOTTOM_MANAGER#getSupportIme()", "method not found")
            return
        }

        runCatching {
            deoptimize(method)
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "getSupportIme", Unit) {
                        if (!AospImeConfig.showAllImeList()) return@open
                        enabledInputMethodList()?.let { param.result = it }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$BOTTOM_MANAGER#getSupportIme()", it)
        }
    }

    /**
     * OS4.0.0.19 calls `addMiuiBottomView` unconditionally from `InputMethodService.onCreate`, so
     * when the AOSP caption bar is the active bar the MIUI bottom view would stack below it. Skip
     * the call entirely for that case; when the AOSP bar is not active (master off, or a
     * MIUI-customized keyboard without the force option) the original runs and the MIUI bottom
     * view remains the bar.
     */
    private fun hookAddMiuiBottomView(bottomManager: Class<*>) {
        // The only overload on OS4.0.0.19 has eight parameters; the resolver requires the exact
        // parameter list to match a unique overload.
        val imsClass = INPUT_METHOD_SERVICE.toClassOrNull()
        val method = if (imsClass != null) {
            CompatibleMethodResolver.find(
                bottomManager,
                "addMiuiBottomView",
                parameterTypes = listOf(
                    Context::class.java,
                    LayoutInflater::class.java,
                    ViewGroup::class.java,
                    ViewGroup::class.java,
                    View::class.java,
                    ViewGroup::class.java,
                    InputMethodManager::class.java,
                    imsClass
                )
            )
        } else null
        if (method == null) {
            DebugLog.hookSkipped(TAG, "$BOTTOM_MANAGER#addMiuiBottomView(...)", "method not found")
            return
        }

        runCatching {
            deoptimize(method)
            method.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "addMiuiBottomView", Unit) {
                        val context = param.args.getOrNull(0) as? Context ?: return@open
                        // Suppression is tied to the AOSP *raise* style: under 小米样式 the MIUI
                        // bottom view must come back (it IS the native bar for optimized
                        // keyboards), otherwise neither bar would render at all.
                        if (AospImeHooker.aospRaiseActive(context)) {
                            param.result = null
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$BOTTOM_MANAGER#addMiuiBottomView(...)", it)
        }
    }

    /**
     * The bottom view's IME-change listener historically pruned keyboards that do not support the
     * MIUI bottom view from the switcher list (A10-era `MiuiSwitchInputMethodListener
     * #deleteNotSupportIme`), re-filtering the list our `getSupportIme` hook just widened. When the
     * method is present, neutralize it so the full enabled list survives.
     */
    private fun hookDeleteNotSupportIme(bottomManager: Class<*>) {
        val listenerName = "$BOTTOM_MANAGER\$MiuiSwitchInputMethodListener"
        val listener = runCatching { bottomManager.classLoader.loadClass(listenerName) }.getOrNull()
            ?: run {
                DebugLog.hookSkipped(TAG, listenerName, "class not found")
                return
            }
        val method = CompatibleMethodResolver.find(listener, "deleteNotSupportIme") ?: run {
            DebugLog.hookSkipped(TAG, "$listenerName#deleteNotSupportIme()", "method not found")
            return
        }

        runCatching {
            method.hook {
                before { param -> param.result = null }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$listenerName#deleteNotSupportIme()", it)
        }
    }

    private fun enabledInputMethodList(): Any? {
        val bottomViewHelper = runCatching {
            sBottomViewHelperField?.get(null)
        }.getOrNull()
        val inputMethodManager = bottomViewHelper?.let { helper ->
            runCatching { mImmFieldOf(helper.javaClass)?.get(helper) }.getOrNull() as? InputMethodManager
        } ?: inputMethodManagerSingleton()
        return inputMethodManager?.let {
            runCatching { it.enabledInputMethodList }.getOrNull()
        }
    }

    private fun mImmFieldOf(helperClass: Class<*>): Field? {
        mImmFieldCache[helperClass]?.let { return it }
        val field = runCatching {
            helperClass.getDeclaredField("mImm").apply { isAccessible = true }
        }.getOrNull()
        mImmFieldCache[helperClass] = field
        return field
    }

    /**
     * Fallback for when `sBottomViewHelper` has not been initialized yet: `InputMethodManager` is
     * a process singleton, so its enabled list is the same one the helper would report.
     */
    private fun inputMethodManagerSingleton(): InputMethodManager? {
        if (immSingletonResolved) return immSingleton
        immSingletonResolved = true
        immSingleton = runCatching {
            "android.view.inputmethod.InputMethodManager".toClassOrNull()
                ?.getDeclaredMethod("peekInstance")
                ?.apply { isAccessible = true }
                ?.invoke(null) as? InputMethodManager
        }.getOrNull() ?: runCatching {
            "android.view.inputmethod.InputMethodManager".toClassOrNull()
                ?.getDeclaredMethod("getInstance")
                ?.apply { isAccessible = true }
                ?.invoke(null) as? InputMethodManager
        }.getOrNull()
        return immSingleton
    }
}