package com.takekazex.hypertweak.hook.rules.systemui.island.flashlight

import android.content.Context
import android.view.View
import java.lang.reflect.Method

/**
 * The flashlight page's haptic (`MiFlashlightController.performHapticFeedback`): the framework's
 * `miui.util.HapticFeedbackUtil` with effect 4 (`EFFECT_ID_BUTTON_LIGHT`) on haptic v2, the
 * `"flick"` effect otherwise, and `View.performHapticFeedback(1)` when neither plays.
 */
internal class FlashlightHaptics(context: Context) {
    private val util: Any? = runCatching {
        Class.forName("miui.util.HapticFeedbackUtil")
            .getConstructor(Context::class.java, Boolean::class.javaPrimitiveType)
            .newInstance(context, false)
    }.getOrNull()
    private val extended: Method? = util?.javaClass?.methods?.firstOrNull {
        it.name == "performExtHapticFeedback" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
    }
    private val named: Method? = util?.javaClass?.methods?.firstOrNull {
        it.name == "performHapticFeedback" &&
            it.parameterTypes.contentEquals(arrayOf(String::class.java, Boolean::class.javaPrimitiveType))
    }

    fun perform(view: View) {
        val played = runCatching {
            extended?.invoke(util, EFFECT_ID_BUTTON_LIGHT) == true || named?.invoke(util, "flick", false) == true
        }.getOrDefault(false)
        if (!played) view.performHapticFeedback(1)
    }

    private companion object {
        const val EFFECT_ID_BUTTON_LIGHT = 4
    }
}
