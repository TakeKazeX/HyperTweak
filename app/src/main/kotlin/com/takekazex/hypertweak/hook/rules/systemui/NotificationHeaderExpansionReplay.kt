package com.takekazex.hypertweak.hook.rules.systemui

import java.lang.reflect.Field
import java.lang.reflect.Method

/** Native expansion caches font size by progress, so replaying an unchanged progress alone is insufficient. */
internal object NotificationHeaderExpansionReplay {
    fun replay(controller: Any, resolvedCallback: Method? = null) {
        val callback = field(controller, "notificationCallback").get(controller)
            ?: error("Missing native notification expansion callback")
        val progress = field(controller, "progress").getFloat(controller)
        val method = resolvedCallback ?: callback.javaClass.methods.singleOrNull {
            it.name == "onExpansionChanged" && it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
        } ?: error("Missing native notification expansion method")
        check(method.declaringClass.isInstance(callback)) { "Wrong native notification callback owner" }
        field(controller, "lastSetTextSizeExpansion").setFloat(controller, -1f)
        // Recompute the host's clockScale/clockScaleEnlarge from its current native font sizes.
        controller.javaClass.getMethod("updateTranslationY").apply { isAccessible = true }.invoke(controller)
        method.apply { isAccessible = true }.invoke(callback, progress)
    }

    private fun field(owner: Any, name: String): Field =
        generateSequence(owner.javaClass) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }
            .firstOrNull() ?: error("Missing native notification controller field: $name")
}
