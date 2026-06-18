package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.atomic.AtomicBoolean

object HideBottomBarHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private val hooksApplied = AtomicBoolean(false)
    @Volatile
    private var hideGestureBarEnabled = false
    @Volatile
    private var raiseLayoutEnabled = false

    override fun onPrepareHotReload() {
        hooksApplied.set(false)
        hideGestureBarEnabled = false
        raiseLayoutEnabled = false
    }

    override fun onHook() {
        hideGestureBarEnabled = Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false)
        raiseLayoutEnabled = Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, false)
        if (!hideGestureBarEnabled) {
            DebugLog.hookSkipped("HideBottomBar", "gesture bar hooks", "disabled")
            return
        }

        // Hook 1: Resources.getDimensionPixelSize
        if (raiseLayoutEnabled) return
        try {
            Resources::class.java.getMethod("getDimensionPixelSize", Int::class.javaPrimitiveType).hook {
                before { param ->
                    if (!hideGestureBarEnabled || raiseLayoutEnabled) return@before
                    val resources = param.thisObject as? Resources ?: return@before
                    val id = param.args[0] as? Int ?: return@before
                    try {
                        val name = resources.getResourceEntryName(id)
                        if (name == "navigation_bar_height") {
                            param.result = 0
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            DebugLog.e("HideBottomBar", "failed to hook Resources.getDimensionPixelSize", t)
        }

        // Dynamic SystemUI classes are hooked from HookEntry.onPackageReady.
    }

    fun onPackageReady(context: Context?, readyClassLoader: ClassLoader) {
        if (hooksApplied.getAndSet(true)) return
        if (context != null) {
            Preferences.initLocalCache(context)
        }

        hideGestureBarEnabled = Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false)
        raiseLayoutEnabled = Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, false)
        if (!hideGestureBarEnabled) return

        applyDynamicHooks(readyClassLoader)
    }

    private fun applyDynamicHooks(cl: ClassLoader) {
        // Hook 3: MiuiDecorationBottomView.onDraw
        try {
            val clzDecorationView = cl.loadClass(
                "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationBottomView"
            )

            val onDrawMethods = clzDecorationView.declaredMethods
                .filter { it.name == "onDraw" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Canvas::class.java }

            if (onDrawMethods.isEmpty()) {
                DebugLog.hookSkipped(
                    "HideBottomBar",
                    "MiuiDecorationBottomView#onDraw(Canvas)",
                    "method not found"
                )
            }

            onDrawMethods.forEach { method ->
                method.hook {
                    before { param ->
                        param.result = null
                    }
                }
            }
        } catch (t: Throwable) {
            DebugLog.hookFailed("HideBottomBar", "MiuiDecorationBottomView#onDraw(Canvas)", t)
        }

        // Hook 4: AuthContainerView.getmBottomHeight
        if (raiseLayoutEnabled) return
        try {
            val clzAuthContainer = cl.loadClass("com.android.systemui.biometrics.AuthContainerView")
            val methods = clzAuthContainer.declaredMethods
                .filter { it.name == "getmBottomHeight" && it.parameterTypes.isEmpty() }
            if (methods.isEmpty()) {
                DebugLog.hookSkipped(
                    "HideBottomBar",
                    "AuthContainerView#getmBottomHeight()",
                    "method not found"
                )
            }
            methods.forEach { method ->
                method.hook {
                    after { param ->
                        param.result = 0
                    }
                }
            }
        } catch (t: Throwable) {
            DebugLog.hookFailed("HideBottomBar", "AuthContainerView#getmBottomHeight()", t)
        }
    }
}
