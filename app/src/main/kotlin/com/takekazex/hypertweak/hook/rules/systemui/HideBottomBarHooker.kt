package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.atomic.AtomicBoolean

object HideBottomBarHooker : StaticHooker() {

    private val hooksApplied = AtomicBoolean(false)

    override fun onHook() {
        DebugLog.d("HideBottomBar", "onHook called")

        // Hook 1: Resources.getDimensionPixelSize
        try {
            Resources::class.java.getMethod("getDimensionPixelSize", Int::class.javaPrimitiveType).hook {
                before { param ->
                    val hideBar = Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false)
                    val raiseLayout = Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, false)
                    if (!hideBar || raiseLayout) return@before
                    val resources = param.thisObject as? Resources ?: return@before
                    val id = param.args[0] as? Int ?: return@before
                    try {
                        val name = resources.getResourceEntryName(id)
                        if (name == "navigation_bar_height") {
                            DebugLog.d("HideBottomBar", "getDimensionPixelSize navigation_bar_height -> 0")
                            param.result = 0
                        }
                    } catch (_: Throwable) {}
                }
            }
            DebugLog.d("HideBottomBar", "hooked Resources.getDimensionPixelSize")
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

        val hideBar = Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false)
        val raiseLayout = Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, false)
        DebugLog.d("HideBottomBar", "package ready hide_gesture_bar=$hideBar gesture_bar_raise_layout=$raiseLayout")

        applyDynamicHooks(readyClassLoader)
    }

    private fun applyDynamicHooks(cl: ClassLoader) {
        // Hook 3: MiuiDecorationBottomView.onDraw
        try {
            val clzDecorationView = cl.loadClass(
                "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationBottomView"
            )
            DebugLog.d("HideBottomBar", "MiuiDecorationBottomView found methods=${clzDecorationView.declaredMethods.map { it.name }}")

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
                        val hideBar = Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false)
                        DebugLog.d("HideBottomBar", "MiuiDecorationBottomView.onDraw hide_gesture_bar=$hideBar")
                        if (hideBar) {
                            param.result = null
                        }
                    }
                }
            }
            DebugLog.d("HideBottomBar", "hooked MiuiDecorationBottomView.onDraw count=${onDrawMethods.size}")
        } catch (t: Throwable) {
            DebugLog.hookFailed("HideBottomBar", "MiuiDecorationBottomView#onDraw(Canvas)", t)
        }

        // Hook 4: AuthContainerView.getmBottomHeight
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
                        if (Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false) &&
                            !Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, false)
                        ) {
                            param.result = 0
                        }
                    }
                }
            }
            DebugLog.d("HideBottomBar", "hooked AuthContainerView.getmBottomHeight")
        } catch (t: Throwable) {
            DebugLog.hookFailed("HideBottomBar", "AuthContainerView#getmBottomHeight()", t)
        }
    }
}
