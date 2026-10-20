package com.takekazex.hypertweak.hook.rules.systemui

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Reads nullable readiness fields without entering the host's throwing/lazy getters. */
internal class ReadyIslandController private constructor(
    private val readiness: List<Field>,
    private val getter: Method,
) {
    fun isReady(view: Any): Boolean = readiness.all { it.get(view) != null }

    fun get(view: Any): Any? = if (isReady(view)) getter.invoke(view) else null

    companion object {
        fun resolve(view: Class<*>, getter: Method, vararg requiredTypes: Class<*>): ReadyIslandController? {
            val fields = requiredTypes.map { type ->
                view.declaredFields.singleOrNull { !Modifier.isStatic(it.modifiers) && it.type == type }
                    ?.apply { isAccessible = true } ?: return null
            }
            return ReadyIslandController(fields, getter.apply { isAccessible = true })
        }
    }
}
