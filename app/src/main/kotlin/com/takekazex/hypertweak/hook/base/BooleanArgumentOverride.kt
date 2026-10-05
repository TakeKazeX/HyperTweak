package com.takekazex.hypertweak.hook.base

/** libxposed Chain.args is a read-only snapshot; replacement arguments go to Chain.proceed(array). */
internal object BooleanArgumentOverride {
    fun copy(types: Array<out Class<*>>, arguments: List<Any?>, indices: IntArray): Array<Any?>? {
        if (types.size != arguments.size || indices.isEmpty() || indices.toSet().size != indices.size) return null
        if (indices.any { it !in types.indices || types[it] != Boolean::class.javaPrimitiveType || arguments[it] !is Boolean }) return null
        return arguments.toTypedArray().also { copy -> indices.forEach { copy[it] = true } }
    }
}
