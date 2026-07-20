package com.takekazex.hypertweak.hook.base

import java.lang.reflect.Method

/** Resolves only a unique, typed overload; ambiguity is intentionally fail-open. */
object CompatibleMethodResolver {
    fun find(
        type: Class<*>, name: String, returnType: Class<*>? = null,
        parameterTypes: List<Class<*>> = emptyList()
    ): Method? {
        val matches = type.declaredMethods.filter { method ->
            method.name == name &&
                (returnType == null || method.returnType == returnType) &&
                method.parameterTypes.size == parameterTypes.size &&
                method.parameterTypes.zip(parameterTypes).all { (actual, expected) -> compatible(actual, expected) }
        }
        return matches.singleOrNull()?.apply { isAccessible = true }
    }

    private fun compatible(actual: Class<*>, expected: Class<*>): Boolean {
        if (actual == expected) return true
        val boxedActual = box(actual)
        val boxedExpected = box(expected)
        return boxedActual == boxedExpected || boxedActual.isAssignableFrom(boxedExpected)
    }

    private fun box(type: Class<*>): Class<*> = when (type) {
        Boolean::class.javaPrimitiveType -> Boolean::class.java
        Byte::class.javaPrimitiveType -> Byte::class.java
        Short::class.javaPrimitiveType -> Short::class.java
        Int::class.javaPrimitiveType -> Int::class.java
        Long::class.javaPrimitiveType -> Long::class.java
        Float::class.javaPrimitiveType -> Float::class.java
        Double::class.javaPrimitiveType -> Double::class.java
        Char::class.javaPrimitiveType -> Char::class.java
        else -> type
    }
}
