package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import java.util.concurrent.atomic.AtomicBoolean

object HideBottomBarHooker : StaticHooker() {

    private val hooksApplied = AtomicBoolean(false)

    override fun onHook() {
        Log.d("HyperTweak", "HideBottomBarHooker: onHook called")

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
                            Log.d("HyperTweak", "HideBottomBarHooker: getDimensionPixelSize navigation_bar_height → 0")
                            param.result = 0
                        }
                    } catch (_: Throwable) {}
                }
            }
            Log.d("HyperTweak", "HideBottomBarHooker: hooked Resources.getDimensionPixelSize")
        } catch (t: Throwable) {
            Log.e("HyperTweak", "HideBottomBarHooker: failed to hook Resources.getDimensionPixelSize", t)
        }

        // Hook 2: Application.attach(Context) to capture the real SystemUI ClassLoader
        val clzApplication = "android.app.Application".toClassOrNull()
        if (clzApplication == null) {
            Log.e("HyperTweak", "HideBottomBarHooker: android.app.Application not found")
            return
        }

        clzApplication.findMethodOrNull {
            name("attach")
            parameterTypes(Context::class.java)
        }?.hook {
            after { param ->
                if (hooksApplied.getAndSet(true)) return@after
                val context = param.args[0] as? Context ?: return@after
                val cl = context.classLoader ?: return@after
                Log.d("HyperTweak", "HideBottomBarHooker: Application.attach fired, classLoader=$cl")

                // Log pref values at this moment in the SystemUI process
                val hideBar = Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false)
                val raiseLayout = Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, false)
                Log.d("HyperTweak", "HideBottomBarHooker: prefs at attach time → hide_gesture_bar=$hideBar, gesture_bar_raise_layout=$raiseLayout")

                applyDynamicHooks(cl)
            }
        } ?: Log.e("HyperTweak", "HideBottomBarHooker: Application.attach not found")
    }

    private fun applyDynamicHooks(cl: ClassLoader) {
        // Hook 3: MiuiDecorationBottomView.onDraw
        try {
            val clzDecorationView = cl.loadClass(
                "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationBottomView"
            )
            Log.d("HyperTweak", "HideBottomBarHooker: MiuiDecorationBottomView found, methods: ${clzDecorationView.declaredMethods.map { it.name }}")

            val onDrawMethods = clzDecorationView.declaredMethods
                .filter { it.name == "onDraw" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Canvas::class.java }

            if (onDrawMethods.isEmpty()) {
                Log.w("HyperTweak", "HideBottomBarHooker: no onDraw(Canvas) found in MiuiDecorationBottomView")
            }

            onDrawMethods.forEach { method ->
                method.hook {
                    before { param ->
                        val hideBar = Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false)
                        Log.d("HyperTweak", "HideBottomBarHooker: MiuiDecorationBottomView.onDraw called, hide_gesture_bar=$hideBar")
                        if (hideBar) {
                            param.result = null
                        }
                    }
                }
            }
            Log.d("HyperTweak", "HideBottomBarHooker: hooked MiuiDecorationBottomView.onDraw (${onDrawMethods.size} methods)")
        } catch (t: Throwable) {
            Log.e("HyperTweak", "HideBottomBarHooker: failed to hook MiuiDecorationBottomView.onDraw", t)
        }

        // Hook 4: AuthContainerView.getmBottomHeight
        try {
            val clzAuthContainer = cl.loadClass("com.android.systemui.biometrics.AuthContainerView")
            clzAuthContainer.declaredMethods
                .filter { it.name == "getmBottomHeight" && it.parameterTypes.isEmpty() }
                .forEach { method ->
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
            Log.d("HyperTweak", "HideBottomBarHooker: hooked AuthContainerView.getmBottomHeight")
        } catch (t: Throwable) {
            Log.e("HyperTweak", "HideBottomBarHooker: AuthContainerView.getmBottomHeight not found (may be OK)", t)
        }
    }
}
