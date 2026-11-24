package com.takekazex.hypertweak.hook.rules.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Leaves the status bar out of system screenshots.
 *
 * Every `com.miui.screenshot` capture of a non-rear display goes through
 * `DisplayCapture.captureDisplay(Context, int, Rect, String[])`, which asks
 * `DisplayCapture.resolveExcludeLayerNames(Context)` for the layer names SurfaceFlinger must skip
 * (`CaptureArgs.Builder.setExcludeOrIncludeLayerNames`). The host already excludes the
 * `StatusBar` layer that way for work-profile captures. While enabled, the resolver's result gains
 * [STATUS_BAR_LAYER], so full, partial and long screenshots are captured without the bar; the
 * bar never disappears from the screen. The lock-screen status bar belongs to the notification
 * shade window and stays in the capture.
 *
 * The switch is read per capture, so it applies live; no host state outlives the hook.
 */
object ScreenshotHideStatusBarHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingEnabled by hookSetting(false) {
        Preferences.getBoolean(ScreenshotSettingsKeys.KEY_SCREENSHOT_HIDE_STATUS_BAR, false)
    }

    private const val TAG = "ScreenshotHideStatusBar"
    private const val DISPLAY_CAPTURE = "com.miui.screenshot.core.util.DisplayCapture"
    /** The status bar window's layer name, as listed in the host's own work-profile exclusions. */
    private const val STATUS_BAR_LAYER = "StatusBar"

    override fun onHook() {
        val capture = DISPLAY_CAPTURE.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, DISPLAY_CAPTURE, "class not found")
            return
        }
        val stringArray = Array<String>::class.java
        val resolver = capture.staticMethod("resolveExcludeLayerNames", stringArray, Context::class.java)
        val captureDisplay = capture.staticMethod("captureDisplay", Bitmap::class.java,
            Context::class.java, Int::class.javaPrimitiveType!!, Rect::class.java, stringArray)
        // The host's work-profile exclusion list proves layer names are matched against StatusBar.
        val hostList = capture.declaredFields.any { field ->
            Modifier.isStatic(field.modifiers) && field.type == stringArray &&
                runCatching { field.isAccessible = true; (field.get(null) as? Array<*>)?.contains(STATUS_BAR_LAYER) }.getOrNull() == true
        }
        if (resolver == null || captureDisplay == null || !hostList) {
            DebugLog.hookSkipped(TAG, "$DISPLAY_CAPTURE contract",
                "resolver=${resolver != null} captureDisplay=${captureDisplay != null} statusBarList=$hostList")
            return
        }
        // The small static resolver can be compiled into its only capture caller.
        if (!deoptimize(captureDisplay)) DebugLog.w(TAG, "could not deoptimize captureDisplay; an inlined resolver bypasses the hook")
        resolver.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "exclude status bar", Unit) {
                    if (!settingEnabled) return@open
                    val names = param.result as? Array<*> ?: return@open
                    if (STATUS_BAR_LAYER in names) return@open
                    param.result = names.filterIsInstance<String>().plus(STATUS_BAR_LAYER).toTypedArray()
                }
            }
        }
        DebugLog.d(TAG, "hooked ${capture.simpleName}#${resolver.name} enabled=$settingEnabled")
    }

    private fun Class<*>.staticMethod(name: String, returns: Class<*>, vararg params: Class<*>): Method? =
        declaredMethods.singleOrNull {
            it.name == name && Modifier.isStatic(it.modifiers) && it.returnType == returns &&
                it.parameterTypes.contentEquals(params)
        }
}
