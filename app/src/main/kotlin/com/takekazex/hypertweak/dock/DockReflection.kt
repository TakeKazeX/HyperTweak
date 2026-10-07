package com.takekazex.hypertweak.dock

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Cached platform reflection shared by the own HWUI host and the injected WMS controller. */
internal object DockReflection {
    private data class Key(val type: Class<*>, val name: String, val args: List<Class<*>?>)
    private val methods = ConcurrentHashMap<Key, Method>()
    private val fields = ConcurrentHashMap<Pair<Class<*>, String>, Field>()
    private val allowed = java.util.concurrent.atomic.AtomicBoolean()
    fun allowOwnViewApis() {
        if (allowed.compareAndSet(false, true)) HiddenApiBypass.addHiddenApiExemptions("Landroid/view/", "Landroid/graphics/")
    }
    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val key = Key(target.javaClass, name, args.map { it?.javaClass })
        val method = methods[key] ?: findMethod(target.javaClass, name, args).also { methods[key] = it }
        return method.invoke(target, *args)
    }
    fun callStatic(type: Class<*>, name: String, vararg args: Any?): Any? {
        val key = Key(type, name, args.map { it?.javaClass })
        val method = methods[key] ?: findMethod(type, name, args).also { methods[key] = it }
        return method.invoke(null, *args)
    }
    private fun findMethod(type: Class<*>, name: String, args: Array<out Any?>): Method {
        var current: Class<*>? = type
        while (current != null) {
            val matches = current.declaredMethods.filter { method ->
                method.name == name && method.parameterTypes.size == args.size &&
                    method.parameterTypes.indices.all { index -> compatible(method.parameterTypes[index], args[index]) }
            }
            if (matches.size == 1) return matches.single().apply { isAccessible = true }
            check(matches.isEmpty()) { "Ambiguous platform method $name" }
            current = current.superclass
        }
        throw NoSuchMethodException("${type.name}#$name/${args.size}")
    }
    private fun compatible(type: Class<*>, value: Any?): Boolean = when {
        value == null -> !type.isPrimitive
        !type.isPrimitive -> type.isInstance(value)
        else -> when (type) {
            Integer.TYPE -> value is Int
            java.lang.Float.TYPE -> value is Float
            java.lang.Boolean.TYPE -> value is Boolean
            java.lang.Long.TYPE -> value is Long
            java.lang.Double.TYPE -> value is Double
            else -> false
        }
    }
    fun field(target: Any, name: String): Field {
        val key = target.javaClass to name
        return fields[key] ?: run {
            var type: Class<*>? = target.javaClass
            while (type != null) {
                type.declaredFields.firstOrNull { it.name == name }?.let { field ->
                    return field.apply { isAccessible = true }.also { fields[key] = it }
                }
                type = type.superclass
            }
            throw NoSuchFieldException(name)
        }
    }
}
