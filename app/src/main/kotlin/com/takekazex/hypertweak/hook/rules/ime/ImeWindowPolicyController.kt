package com.takekazex.hypertweak.hook.rules.ime

import android.inputmethodservice.InputMethodService
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.window.OnBackInvokedCallback
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Own the IME's caption visibility and default back registration, without changing app windows,
 * manifest flags, custom back callbacks, or the animation implementation in the client process. */
internal class ImeWindowPolicyController(owner: BaseHooker) : HookComponent(owner) {
    private class Caption(service: InputMethodService, var nativeVisible: Boolean) {
        val service = WeakReference(service)
    }
    private val captions = WeakHashMap<WindowInsetsController, Caption>()
    private val restored = WeakHashMap<WindowInsetsController, Boolean>()
    private val backPolicies = WeakHashMap<InputMethodService, Boolean>()
    private var syncing = false
    private var alive = true

    fun install() {
        alive = true
        installCaptionPolicy()
        val ims = InputMethodService::class.java
        val register = HostAccess.requireDeclaredMethod(ims, "registerDefaultOnBackInvokedCallback", accessible = true)
        deoptimize(register)
        // Verified direct framework caller; cover an already compiled show path as well.
        HostAccess.declaredMethod(ims, "showWindow", Boolean::class.javaPrimitiveType!!, accessible = true)?.let(::deoptimize)
        register.hook {
            before { param ->
                HookFailurePolicy.open("AospIme", "predictive IME back registration", Unit) {
                    val service = param.thisObject as? InputMethodService ?: return@open
                    if (!alive || !AospImeHooker.managed(service)) return@open
                    if (HostAccess.read(service, "mBackCallbackRegistered") == true) {
                        param.result = null
                        return@open
                    }
                    val dialog = service.window ?: return@open
                    val callback = HostAccess.read(service, "mCompatBackCallback") as? OnBackInvokedCallback ?: return@open
                    val dispatcher = HostAccess.method(dialog, "getOnBackInvokedDispatcher")?.invoke(dialog) ?: return@open
                    val systemRegister = HostAccess.method(dispatcher, "registerSystemOnBackInvokedCallback",
                        OnBackInvokedCallback::class.java) ?: return@open
                    systemRegister.invoke(dispatcher, callback)
                    HostAccess.requireDeclaredField(ims, "mBackCallbackRegistered", accessible = true).setBoolean(service, true)
                    param.result = null
                    DebugLog.i("AospIme", "predictive IME default callback registered as system")
                }
            }
        }
        HostAccess.declaredMethod(ims, "showWindow", Boolean::class.javaPrimitiveType!!, accessible = true)?.hook {
            after { param ->
                HookFailurePolicy.open("AospIme", "shown IME window policy", Unit) {
                    (param.thisObject as? InputMethodService)?.let(::refresh)
                }
            }
        }
    }

    private fun installCaptionPolicy() {
        val type = "android.view.InsetsController".toClassOrNull() ?: return
        val caption = WindowInsets.Type.captionBar()
        // Sogou requests hidden system bars, which includes the locally provided IME caption.
        // Preserve the other requested types, including IME hide and predictive-back requests.
        type.declaredMethods.filter { it.name == "hide" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType &&
            (it.parameterCount == 1 || (it.parameterCount == 2 && it.parameterTypes[1].name == "android.view.inputmethod.ImeTracker\$Token"))
        }.forEach { method ->
            deoptimize(method)
            method.hook {
                before { param ->
                    HookFailurePolicy.open("AospIme", "IME caption hide policy", Unit) {
                        val state = captions[param.thisObject] ?: return@open
                        if (!ownsCaption(state) || syncing) return@open
                        val types = param.args[0] as? Int ?: return@open
                        if (types and caption != 0) {
                            state.nativeVisible = false
                            param.args[0] = types and caption.inv()
                            if (param.args[0] == 0) param.result = null
                        }
                    }
                }
            }
        }
        val requested = HostAccess.requireDeclaredMethod(type, "setRequestedVisibleTypes",
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, accessible = true)
        deoptimize(requested)
        requested.hook {
            before { param ->
                HookFailurePolicy.open("AospIme", "IME requested caption visibility", Unit) {
                    val state = captions[param.thisObject] ?: return@open
                    if (!ownsCaption(state) || syncing) return@open
                    val mask = param.args[1] as? Int ?: return@open
                    val visible = param.args[0] as? Int ?: return@open
                    if (mask and caption != 0) {
                        state.nativeVisible = visible and caption != 0
                        param.args[0] = visible or caption
                    }
                }
            }
        }
    }

    private fun ownsCaption(state: Caption): Boolean {
        val service = state.service.get() ?: return false
        return alive && AospImeHooker.aospRaiseActive(service) && !service.isFullscreenMode &&
            !service.isExtractViewShown && service.isInputViewShown
    }

    fun refresh(service: InputMethodService) {
        if (!alive) return
        refreshBack(service)
        val decor = service.window?.window?.decorView ?: return
        val controller = decor.windowInsetsController ?: return
        val wanted = AospImeHooker.aospRaiseActive(service) && !service.isFullscreenMode &&
            !service.isExtractViewShown && service.isInputViewShown
        val old = captions[controller]
        if (wanted) {
            if (old == null) {
                val flags = HostAccess.read(controller, "mRequestedVisibleTypes") as? Int ?: return
                captions[controller] = Caption(service, restored.remove(controller) ?: (flags and WindowInsets.Type.captionBar() != 0))
            }
            if (decor.rootWindowInsets?.isVisible(WindowInsets.Type.captionBar()) != true) {
                withInternalUpdate { controller.show(WindowInsets.Type.captionBar()) }
                decor.requestApplyInsets()
                DebugLog.i("AospIme", "AOSP caption visibility restored")
            }
        } else if (old != null) {
            captions.remove(controller)
            withInternalUpdate {
                if (old.nativeVisible) controller.show(WindowInsets.Type.captionBar())
                else controller.hide(WindowInsets.Type.captionBar())
            }
            decor.requestApplyInsets()
        } else restored.remove(controller)?.let { visible ->
            withInternalUpdate {
                if (visible) controller.show(WindowInsets.Type.captionBar()) else controller.hide(WindowInsets.Type.captionBar())
            }
        }
    }

    private fun refreshBack(service: InputMethodService) {
        val wanted = AospImeHooker.managed(service)
        if (backPolicies[service] == wanted) return
        // Recover already registered callbacks after an upgrade, and restore native registration
        // on disable/deselect. Never register a hidden service that has no active default callback.
        if (HostAccess.read(service, "mBackCallbackRegistered") == true) {
            HostAccess.requireDeclaredMethod(InputMethodService::class.java, "unregisterDefaultOnBackInvokedCallback", accessible = true).invoke(service)
            HostAccess.requireDeclaredMethod(InputMethodService::class.java, "registerDefaultOnBackInvokedCallback", accessible = true).invoke(service)
        }
        backPolicies[service] = wanted
    }

    private inline fun withInternalUpdate(action: () -> Unit) {
        val previous = syncing
        syncing = true
        try { action() } finally { syncing = previous }
    }

    fun saveCaptions(): List<Any> = captions.map { (controller, state) -> arrayOf(controller, state.nativeVisible) } +
        restored.map { (controller, visible) -> arrayOf(controller, visible) }

    fun restoreCaptions(value: Any?) {
        (value as? List<*>)?.forEach { item ->
            val parts = item as? Array<*> ?: return@forEach
            val controller = parts.getOrNull(0) as? WindowInsetsController ?: return@forEach
            val visible = parts.getOrNull(1) as? Boolean ?: return@forEach
            restored[controller] = visible
        }
    }

    fun retire() { alive = false; backPolicies.clear() }
}
