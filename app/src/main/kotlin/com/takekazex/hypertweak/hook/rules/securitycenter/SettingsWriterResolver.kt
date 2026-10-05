package com.takekazex.hypertweak.hook.rules.securitycenter

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Select only Settings writers whose Boolean result the interceptor can preserve. */
internal object SettingsWriterResolver {
    fun select(methods: Array<Method>, contentResolverType: Class<*>): List<Method> =
        methods.filter { method ->
            val valueType = when (method.name) {
                "putInt" -> Int::class.javaPrimitiveType
                "putString" -> String::class.java
                else -> return@filter false
            }
            Modifier.isStatic(method.modifiers) &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.contentEquals(
                    arrayOf(contentResolverType, String::class.java, valueType),
                )
        }
}
