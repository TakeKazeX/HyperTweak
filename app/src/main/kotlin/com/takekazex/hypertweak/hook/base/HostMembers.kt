package com.takekazex.hypertweak.hook.base

import java.lang.reflect.Method

/** Shared loader-scoped reflection, including cached misses. No Android lifecycle dependency. */
internal object HostMembers {
    private data class MethodKey(val name: String, val parameters: List<Class<*>>)
    private class Members {
        val fields = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.lang.reflect.Field>>()
        val methods = java.util.concurrent.ConcurrentHashMap<MethodKey, java.util.Optional<Method>>()
    }
    // ClassValue follows the actual defining loader and cannot pin it through a global Class map.
    private val members = object : ClassValue<Members>() {
        override fun computeValue(type: Class<*>): Members = Members()
    }
    /** Cache both present and absent members by their defining Class/loader. */
    fun field(type: Class<*>, name: String): java.lang.reflect.Field? =
        members.get(type).fields.computeIfAbsent(name) {
            java.util.Optional.ofNullable(generateSequence(type) { it.superclass }
                .mapNotNull { current -> runCatching { current.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }
                .firstOrNull())
        }.orElse(null)

    fun read(owner: Any, name: String): Any? = field(owner.javaClass, name)?.get(owner)

    fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method? =
        members.get(type).methods.computeIfAbsent(MethodKey(name, parameters.toList())) {
            java.util.Optional.ofNullable(generateSequence(type) { it.superclass }
                .mapNotNull { current -> runCatching { current.getDeclaredMethod(name, *parameters).apply { isAccessible = true } }.getOrNull() }
                .firstOrNull()
                ?: runCatching { type.getMethod(name, *parameters).apply { isAccessible = true } }.getOrNull())
        }.orElse(null)

    fun method(owner: Any, name: String, vararg parameters: Class<*>): Method? =
        method(owner.javaClass, name, *parameters)

}
