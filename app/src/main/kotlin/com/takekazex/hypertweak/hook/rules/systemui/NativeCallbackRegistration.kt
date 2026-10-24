package com.takekazex.hypertweak.hook.rules.systemui

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** A compiler bridge accepting Object must not compete with the actual typed registration API. */
internal object NativeCallbackRegistration {
    fun select(methods: Array<Method>, name: String, callback: Class<*>): Method? = methods.filter {
        it.name == name && !it.isBridge && !it.isSynthetic && !Modifier.isStatic(it.modifiers) &&
            it.returnType == Void.TYPE && it.parameterCount == 1 && it.parameterTypes[0] != Any::class.java &&
            it.parameterTypes[0].isAssignableFrom(callback)
    }.singleOrNull()
}
