package com.takekazex.hypertweak.hook.rules.personalassistant

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Required container operation, excluding same-signature default animation helpers. */
internal object NativeWidgetAddContract {
    fun isRequiredOperation(modifiers: Int) = Modifier.isAbstract(modifiers) && !Modifier.isStatic(modifiers)

    fun select(methods: Collection<Method>, view: Class<*>, model: Class<*>): Method? = methods.singleOrNull {
        isRequiredOperation(it.modifiers) && it.returnType == Void.TYPE && it.parameterCount == 2 &&
            it.parameterTypes[0] == view && it.parameterTypes[1].isAssignableFrom(model)
    }
}
