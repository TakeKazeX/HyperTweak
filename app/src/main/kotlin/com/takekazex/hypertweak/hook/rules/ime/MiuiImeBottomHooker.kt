package com.takekazex.hypertweak.hook.rules.ime

import com.takekazex.hypertweak.hook.base.cachedDeclaredMethod
import com.takekazex.hypertweak.hook.base.HostAccess
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field

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
class MiuiImeBottomHooker : StaticHooker() {
    private companion object {
        const val TAG = "MiuiImeBottom"

        const val BOTTOM_MANAGER = "com.miui.inputmethod.InputMethodBottomManager"
        const val INPUT_METHOD_SERVICE = "android.inputmethodservice.InputMethodService"

    }

    /** Resolved once at hook time; `getSupportIme` fires on every switcher list request. */
    private var sBottomViewHelperField: Field? by generationState { null }

    /** Process-wide `InputMethodManager` fallback when the bottom-view helper is not ready. */
    private var immSingleton: InputMethodManager? by generationState { null }

    override fun onHook() {
        val bottomManager = BOTTOM_MANAGER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BOTTOM_MANAGER, "class not found")
            return
        }

        sBottomViewHelperField = runCatching {
            HostAccess.requireDeclaredField(bottomManager, "sBottomViewHelper", accessible = true)
        }.getOrNull()

        hookGetSupportIme(bottomManager)
        hookAddMiuiBottomView(bottomManager)
        hookDeleteNotSupportIme(bottomManager)
        hookNativeStyle(bottomManager)
    }

    /**
     * The switcher popup is built from `getSupportIme()`; the original prunes the enabled list by
     * `sImeMinVersionSupport`, so return a fresh full enabled list before its destructive filter when the
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
                before { param ->
                    HookFailurePolicy.open(TAG, "getSupportIme", Unit) {
                        if (!listAllActive()) return@open
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
     * the call entirely for that case; when the AOSP bar is not active (master off or HyperOS style) the original runs and the MIUI bottom
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
                        val context = param.args.getOrNull(7) as? Context ?: return@open
                        // Suppression is tied to the AOSP *raise* style: under 小米样式 the MIUI
                        // bottom view must come back (it IS the native bar for optimized
                        // keyboards), otherwise neither bar would render at all.
                        if (AospImeHooker.aospRaiseActive(context)) {
                            param.result = null
                        }
                    }
                }
                after { param ->
                    HookFailurePolicy.open(TAG, "native bottom appearance", Unit) {
                        val service = param.args.getOrNull(7) as? android.inputmethodservice.InputMethodService ?: return@open
                        AospImeHooker.onNativeBottomReady(service, bottomManager)
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
        // Class.classLoader is annotated @RecentlyNullable; a loaded host class always has one.
        val listener = runCatching { requireNotNull(bottomManager.classLoader).loadClass(listenerName) }.getOrNull()
            ?: run {
                DebugLog.hookSkipped(TAG, listenerName, "class not found")
                return
            }
        val method = CompatibleMethodResolver.find(listener, "deleteNotSupportIme") ?: run {
            DebugLog.hookSkippedDebug(TAG, "$listenerName#deleteNotSupportIme()", "method not found")
            return
        }

        runCatching {
            method.hook {
                before { param -> if (listAllActive()) param.result = null }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$listenerName#deleteNotSupportIme()", it)
        }
    }

    private fun hookNativeStyle(bottomManager: Class<*>) {
        val serviceClass = INPUT_METHOD_SERVICE.toClassOrNull() ?: return
        HostAccess.declaredMethod(bottomManager, "isImeSupport", serviceClass, accessible = true)?.let { method ->
            deoptimize(method)
            method.hook {
                before { param ->
                    if (AospImeHooker.miuiStyleActive(param.args.firstOrNull() as? Context)) param.result = true
                }
            }
        }
        val util = "com.miui.inputmethod.InputMethodUtil".toClassOrNull() ?: return
        HostAccess.declaredMethod(util, "isMiuiBottomEnabled", Context::class.java, accessible = true)?.let { method ->
            deoptimize(method)
            method.hook {
                before { param ->
                    // This argument is the phrase module context, not the keyboard context.
                    HookFailurePolicy.open(TAG, "native bottom enabled", Unit) {
                        if (AospImeHooker.miuiStyleActive(AospImeHooker.currentServiceContext())) param.result = true
                    }
                }
            }
        }
    }

    private fun listAllActive(): Boolean = AospImeConfig.showAllImeList() &&
        AospImeConfig.isSelectedIme(hookParam.packageName)

    private fun enabledInputMethodList(): Any? {
        val bottomViewHelper = runCatching {
            sBottomViewHelperField?.get(null)
        }.getOrNull()
        val inputMethodManager = bottomViewHelper?.let { helper ->
            runCatching { mImmFieldOf(helper.javaClass)?.get(helper) }.getOrNull() as? InputMethodManager
        } ?: inputMethodManagerSingleton()
        return inputMethodManager?.let {
            runCatching { ArrayList(it.enabledInputMethodList) }.getOrNull()
        }
    }

    private fun mImmFieldOf(helperClass: Class<*>): Field? = HostAccess.declaredField(helperClass, "mImm", accessible = true)

    /**
     * Fallback for when `sBottomViewHelper` has not been initialized yet: `InputMethodManager` is
     * a process singleton, so its enabled list is the same one the helper would report.
     */
    private fun inputMethodManagerSingleton(): InputMethodManager? {
        immSingleton?.let { return it }
        immSingleton = runCatching {
            "android.view.inputmethod.InputMethodManager".toClassOrNull()
                ?.cachedDeclaredMethod("peekInstance", accessible = true)
                ?.invoke(null) as? InputMethodManager
        }.getOrNull() ?: runCatching {
            "android.view.inputmethod.InputMethodManager".toClassOrNull()
                ?.cachedDeclaredMethod("getInstance", accessible = true)
                ?.invoke(null) as? InputMethodManager
        }.getOrNull()
        return immSingleton
    }
}
