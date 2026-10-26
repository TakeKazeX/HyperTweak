package com.takekazex.hypertweak.hook.base

import java.lang.reflect.Method

/** Shared loader-scoped reflection. Cached handles are borrowed: callers must not change access flags. */
internal object HostMembers {
    private data class MethodKey(val name: String, val parameters: List<Class<*>?>)
    private enum class Lookup { DECLARED, PUBLIC }
    private data class FieldKey(val lookup: Lookup, val name: String, val accessible: Boolean)
    private data class ExactMethodKey(val lookup: Lookup, val signature: MethodKey, val accessible: Boolean)
    private data class ContractValue(val type: Class<*>, val value: java.util.Optional<Any>)
    private class Members {
        val fields = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.lang.reflect.Field>>()
        val methods = java.util.concurrent.ConcurrentHashMap<MethodKey, java.util.Optional<Method>>()
        val exactFields = java.util.concurrent.ConcurrentHashMap<FieldKey, java.util.Optional<java.lang.reflect.Field>>()
        val exactMethods = java.util.concurrent.ConcurrentHashMap<ExactMethodKey, java.util.Optional<Method>>()
        val contracts = java.util.concurrent.ConcurrentHashMap<String, ContractValue>()
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

    /** Inherited-table handles are always accessible=true. Never share them with exactMethods,
     * whose handles retain the explicitly requested access policy. */
    fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method? =
        members.get(type).methods.computeIfAbsent(MethodKey(name, parameters.toList())) {
            java.util.Optional.ofNullable(generateSequence(type) { it.superclass }
                .mapNotNull { current -> runCatching { current.getDeclaredMethod(name, *parameters).apply { isAccessible = true } }.getOrNull() }
                .firstOrNull()
                ?: runCatching { type.getMethod(name, *parameters).apply { isAccessible = true } }.getOrNull())
        }.orElse(null)

    fun method(owner: Any, name: String, vararg parameters: Class<*>): Method? =
        method(owner.javaClass, name, *parameters)

    /** Cache metadata/misses; resolver exceptions are retried. A contract has one result type. */
    fun <T : Any> cached(type: Class<*>, contract: String, resultType: Class<T>, resolve: () -> T?): T? {
        val entry = members.get(type).contracts.computeIfAbsent(contract) {
            ContractValue(resultType, java.util.Optional.ofNullable(resultType.cast(resolve())))
        }
        require(entry.type == resultType) {
            "Reflection contract ${type.name}#$contract already uses ${entry.type.name}, requested ${resultType.name}"
        }
        return resultType.cast(entry.value.orElse(null))
    }

    private fun exactField(type: Class<*>, name: String, lookup: Lookup, accessible: Boolean = false): java.lang.reflect.Field? =
        members.get(type).exactFields.computeIfAbsent(FieldKey(lookup, name, accessible)) {
            java.util.Optional.ofNullable(try {
                (if (lookup == Lookup.DECLARED) type.getDeclaredField(name) else type.getField(name)).apply { if (accessible) isAccessible = true }
            } catch (_: NoSuchFieldException) { null })
        }.orElse(null)

    private fun exactMethod(type: Class<*>, name: String, parameters: Array<out Class<*>?>, lookup: Lookup, accessible: Boolean = false): Method? =
        members.get(type).exactMethods.computeIfAbsent(ExactMethodKey(lookup, MethodKey(name, parameters.toList()), accessible)) {
            java.util.Optional.ofNullable(try {
                (if (lookup == Lookup.DECLARED) type.getDeclaredMethod(name, *parameters) else type.getMethod(name, *parameters)).apply { if (accessible) isAccessible = true }
            } catch (_: NoSuchMethodException) { null })
        }.orElse(null)

    fun declaredField(type: Class<*>, name: String, accessible: Boolean): java.lang.reflect.Field? = exactField(type, name, Lookup.DECLARED, accessible)
    fun publicField(type: Class<*>, name: String, accessible: Boolean): java.lang.reflect.Field? = exactField(type, name, Lookup.PUBLIC, accessible)
    fun declaredMethod(type: Class<*>, name: String, vararg parameters: Class<*>, accessible: Boolean): Method? =
        exactMethod(type, name, parameters, Lookup.DECLARED, accessible)
    fun publicMethod(type: Class<*>, name: String, vararg parameters: Class<*>, accessible: Boolean): Method? =
        exactMethod(type, name, parameters, Lookup.PUBLIC, accessible)

    fun requireDeclaredField(type: Class<*>, name: String, accessible: Boolean): java.lang.reflect.Field =
        exactField(type, name, Lookup.DECLARED, accessible) ?: throw NoSuchFieldException("${type.name}#$name")
    fun requirePublicField(type: Class<*>, name: String, accessible: Boolean): java.lang.reflect.Field =
        exactField(type, name, Lookup.PUBLIC, accessible) ?: throw NoSuchFieldException("${type.name}#$name")
    fun requireDeclaredMethod(type: Class<*>, name: String, vararg parameters: Class<*>?, accessible: Boolean): Method =
        exactMethod(type, name, parameters, Lookup.DECLARED, accessible) ?: throw NoSuchMethodException("${type.name}#$name")
    fun requirePublicMethod(type: Class<*>, name: String, vararg parameters: Class<*>?, accessible: Boolean): Method =
        exactMethod(type, name, parameters, Lookup.PUBLIC, accessible) ?: throw NoSuchMethodException("${type.name}#$name")
}
