package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.content.res.Configuration
import com.takekazex.hypertweak.settings.contract.NotificationHeaderModel
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Native clock baselines survive live reapplication and restore before generation retirement. */
internal class NotificationHeaderClockSizing(
    private val field: (Class<*>, String) -> Field?,
    private val read: (Any, String) -> Any?,
    private val resolveClass: (String) -> Class<*>?,
    private val timeScale: () -> Float,
) {
    private companion object { const val MIUI_CONFIGS_CLASS = "com.miui.utils.configs.MiuiConfigs" }
    data class ControllerSizeState(
        val bigTimeSize: Int,
        val shadeSize: Int,
        val flipSize: Int
    )

    val controllerSizes = WeakHashMap<Any, ControllerSizeState>()
    private var verticalModeMethod: Method? = null
    private var landscapeModeMethod: Method? = null
    @Suppress("DiscouragedApi")
    fun applyClockSize(controller: Any) {
        val bigField = field(controller.javaClass, "bigTimeSize") ?: return
        val shadeField = field(controller.javaClass, "shadeHeaderNotificationClockTextSize") ?: return
        val flipField = field(controller.javaClass, "qsControlHeaderClockFlipSize") ?: return
        controllerSizes.putIfAbsent(
            controller,
            ControllerSizeState(
                bigField.getInt(controller),
                shadeField.getInt(controller),
                flipField.getInt(controller)
            )
        )
        val context = read(controller, "context") as? Context ?: return
        val resources = context.resources
        val shadeId = resources.getIdentifier(
            "shade_header_notification_clock_text_size",
            "dimen",
            "com.android.systemui"
        )
        val flipId = resources.getIdentifier(
            "qs_control_header_clock_flip_size",
            "dimen",
            "com.android.systemui"
        )
        if (shadeId == 0 || flipId == 0) return
        val sizeScale = if (isCustomPortraitMode(context)) {
            timeScale()
        } else {
            NotificationHeaderModel.DEFAULT_TIME_SCALE
        }
        val shadeSize = (resources.getDimensionPixelSize(shadeId) * sizeScale).roundToInt()
        val flipSize = (resources.getDimensionPixelSize(flipId) * sizeScale).roundToInt()
        shadeField.setInt(controller, shadeSize)
        flipField.setInt(controller, flipSize)
        bigField.setInt(controller, if (isFlipTinyScreen(context)) flipSize else shadeSize)
    }

    fun isVerticalMode(context: Context): Boolean {
        val method = verticalModeMethod ?: resolveMiuiConfigMethod("isVerticalMode")
            ?.also { verticalModeMethod = it }
        return runCatching { method?.invoke(null, context) as? Boolean }.getOrNull()
            ?: (context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE)
    }

    fun isLandscapeMode(context: Context): Boolean {
        val method = landscapeModeMethod ?: resolveMiuiConfigMethod("isLandscape")
            ?.also { landscapeModeMethod = it }
        return runCatching { method?.invoke(null, context) as? Boolean }.getOrNull()
            ?: (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun isCustomPortraitMode(context: Context): Boolean =
        !isLandscapeMode(context) &&
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
            isVerticalMode(context)

    private fun isFlipTinyScreen(context: Context): Boolean = runCatching {
        resolveMiuiConfigMethod("isFlipTinyScreen")?.invoke(null, context) as? Boolean
    }.getOrNull() ?: false

    private fun resolveMiuiConfigMethod(name: String): Method? =
        resolveClass(MIUI_CONFIGS_CLASS)?.declaredMethods?.firstOrNull {
            it.name == name && it.parameterCount == 1 &&
                Context::class.java.isAssignableFrom(it.parameterTypes[0])
        }?.apply { isAccessible = true }

    fun restore() {
            controllerSizes.forEach { (controller, state) ->
                runCatching {
                    field(controller.javaClass, "bigTimeSize")?.setInt(controller, state.bigTimeSize)
                    field(controller.javaClass, "shadeHeaderNotificationClockTextSize")
                        ?.setInt(controller, state.shadeSize)
                    field(controller.javaClass, "qsControlHeaderClockFlipSize")
                        ?.setInt(controller, state.flipSize)
                }
            }
    }
    fun clearConfigMethods() { verticalModeMethod = null; landscapeModeMethod = null }
}
