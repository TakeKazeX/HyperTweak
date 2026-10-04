package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHostAccess
import java.util.WeakHashMap
import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

object HideLockscreenStatusBarHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    @Volatile
    private var enabled = false
    private var keyguardStatusBarClass: Class<*>? = null

    private data class Original(val visibility: Int, val alpha: Float)
    private val originals = WeakHashMap<View, Original>()
    private var applying = false

    override fun saveHotReloadState(): Any = StatusIconHostAccess.onMain { originals.keys.toList() }
    override fun restoreHotReloadState(state: Any?) {
        StatusIconHostAccess.onMain { recoverExistingViews((state as? List<*>)?.filterIsInstance<View>().orEmpty()) }
    }
    internal fun recoverExistingViews(views: List<View>) {
        if (!enabled) return
        views.filter { keyguardStatusBarClass?.isInstance(it) == true }.forEach(::hide)
    }

    override fun onPrepareHotReload() {
        enabled = false
        StatusIconHostAccess.onMain {
            originals.forEach { (view, state) -> view.visibility = state.visibility; view.alpha = state.alpha }
            originals.clear()
        }
        keyguardStatusBarClass = null
    }

    override fun onHook() {
        enabled = Preferences.getBoolean(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false)
        if (!enabled) {
            DebugLog.hookSkippedDebug("HideLockscreenStatusBar", "keyguard status bar hooks", "disabled")
            return
        }

        val targetClass = runCatching {
            classLoader.loadClass("com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView")
        }.getOrElse {
            DebugLog.hookSkipped("HideLockscreenStatusBar", "MiuiKeyguardStatusBarView", "class not found")
            return
        }
        keyguardStatusBarClass = targetClass

        targetClass.declaredMethods.firstOrNull {
            it.name == "onFinishInflate" && it.parameterTypes.isEmpty()
        }?.hook {
            after { param ->
                val view = param.thisObject as? View ?: return@after
                hide(view)
            }
        } ?: DebugLog.hookSkipped(
            "HideLockscreenStatusBar",
            "MiuiKeyguardStatusBarView#onFinishInflate",
            "method not found"
        )

        runCatching {
            View::class.java.getMethod("setVisibility", Int::class.javaPrimitiveType).hook {
                before { param ->
                    if (shouldHide(param.thisObject)) {
                        val view = param.thisObject as? View ?: return@before
                        val original = originals.getOrPut(view) { Original(view.visibility, view.alpha) }
                        if (!applying) originals[view] = original.copy(visibility = param.args[0] as Int)
                        param.args[0] = View.INVISIBLE
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed("HideLockscreenStatusBar", "View#setVisibility(Int)", it)
        }

        runCatching {
            View::class.java.getMethod("setAlpha", Float::class.javaPrimitiveType).hook {
                before { param ->
                    if (shouldHide(param.thisObject)) {
                        val view = param.thisObject as? View ?: return@before
                        val original = originals.getOrPut(view) { Original(view.visibility, view.alpha) }
                        if (!applying) originals[view] = original.copy(alpha = param.args[0] as Float)
                        param.args[0] = 0f
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed("HideLockscreenStatusBar", "View#setAlpha(Float)", it)
        }
    }

    private fun shouldHide(instance: Any?): Boolean {
        return enabled && instance != null && keyguardStatusBarClass?.isInstance(instance) == true
    }

    private fun hide(view: View) {
        if (!enabled) return
        originals.putIfAbsent(view, Original(view.visibility, view.alpha))
        applying = true
        try { runCatching {
            view.visibility = View.INVISIBLE
            view.alpha = 0f
        }.onFailure {
            DebugLog.w("HideLockscreenStatusBar", "failed to hide keyguard status bar", it)
        } } finally { applying = false }
    }
}
