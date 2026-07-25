package com.takekazex.hypertweak.hook.rules.systemui

import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

object HideLockscreenStatusBarHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    @Volatile
    private var enabled = false
    private var keyguardStatusBarClass: Class<*>? = null

    override fun onPrepareHotReload() {
        enabled = false
        keyguardStatusBarClass = null
    }

    override fun onHook() {
        enabled = Preferences.getBoolean(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false)
        if (!enabled) {
            DebugLog.hookSkipped("HideLockscreenStatusBar", "keyguard status bar hooks", "disabled")
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
        runCatching {
            view.visibility = View.INVISIBLE
            view.alpha = 0f
        }.onFailure {
            DebugLog.w("HideLockscreenStatusBar", "failed to hide keyguard status bar", it)
        }
    }
}
