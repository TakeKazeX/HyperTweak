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

    private val navigationHandleClassNames = listOf(
        "com.android.systemui.navigationbar.gestural.NavigationHandle",
        "com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle"
    )

    private val hooksApplied = AtomicBoolean(false)
    @Volatile
    private var hideGestureBarEnabled = false
    @Volatile
    private var raiseLayoutEnabled = false

    /**
     * Compiled resource ids of `navigation_bar_height` in the `android` and
     * `com.android.systemui` packages. The `Resources.getDimensionPixelSize` hook compares the
     * requested id against these instead of resolving the resource name on every call (that was
     * three `Resources` table lookups per dimension read anywhere in SystemUI). 0 = not resolved.
     */
    @Volatile
    private var navBarHeightAndroidResId = 0
    @Volatile
    private var navBarHeightSystemUiResId = 0

    override fun onPrepareHotReload() {
        hooksApplied.set(false)
        hideGestureBarEnabled = false
        raiseLayoutEnabled = false
        navBarHeightAndroidResId = 0
        navBarHeightSystemUiResId = 0
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
            // The `android` package id is process-wide and resolvable without an app context;
            // the SystemUI package id is resolved at package ready.
            runCatching {
                navBarHeightAndroidResId = Resources.getSystem()
                    .getIdentifier("navigation_bar_height", "dimen", "android")
            }
            Resources::class.java.getMethod("getDimensionPixelSize", Int::class.javaPrimitiveType).hook {
                before { param ->
                    if (!hideGestureBarEnabled || raiseLayoutEnabled) return@before
                    val resources = param.thisObject as? Resources ?: return@before
                    val id = param.args[0] as? Int ?: return@before
                    if (id != navBarHeightAndroidResId && id != navBarHeightSystemUiResId) return@before
                    try {
                        // Rare match: keep the original name/type/package verification so a
                        // resource id that numerically collides in another package is not zeroed.
                        val name = resources.getResourceEntryName(id)
                        val type = resources.getResourceTypeName(id)
                        val pkg = resources.getResourcePackageName(id)
                        if (name == "navigation_bar_height" && type == "dimen" &&
                            (pkg == "android" || pkg == "com.android.systemui")) {
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
            resolveNavigationBarHeightResIds(context)
        }

        hideGestureBarEnabled = Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false)
        raiseLayoutEnabled = Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, false)
        if (!hideGestureBarEnabled) return

        applyDynamicHooks(readyClassLoader)
    }

    private fun resolveNavigationBarHeightResIds(context: Context) {
        runCatching {
            navBarHeightSystemUiResId = context.resources.getIdentifier(
                "navigation_bar_height", "dimen", "com.android.systemui"
            )
        }
    }

    private fun applyDynamicHooks(cl: ClassLoader) {
        // Keep the handle views attached and suppress only the gesture bar drawing.
        navigationHandleClassNames.forEach { className ->
            hookOnDraw(cl, className)
        }

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

    private fun hookOnDraw(cl: ClassLoader, className: String) {
        val target = "${className.substringAfterLast('.')}#onDraw(Canvas)"
        val targetClass = try {
            cl.loadClass(className)
        } catch (_: ClassNotFoundException) {
            DebugLog.hookSkipped("HideBottomBar", target, "class not found")
            return
        } catch (t: Throwable) {
            DebugLog.hookFailed("HideBottomBar", target, t)
            return
        }

        try {
            val methods = targetClass.declaredMethods.filter {
                it.name == "onDraw" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Canvas::class.java
            }
            if (methods.isEmpty()) {
                DebugLog.hookSkipped("HideBottomBar", target, "method not found")
                return
            }

            methods.forEach { method ->
                method.hook {
                    before { param ->
                        if (hideGestureBarEnabled) {
                            param.result = null
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            DebugLog.hookFailed("HideBottomBar", target, t)
        }
    }
}
