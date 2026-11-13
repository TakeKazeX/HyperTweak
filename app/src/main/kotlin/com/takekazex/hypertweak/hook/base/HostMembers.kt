package com.takekazex.hypertweak.hook.base

import java.lang.reflect.Method
import java.lang.reflect.Field
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** Shared loader-scoped reflection. Cached handles are borrowed: callers must not change access flags. */
internal object HostMembers {
    private const val REQUIRED_INHERITED_FIELD_POLICY = 4
    internal val noParameters = emptyArray<Class<*>>()
    private enum class Lookup { DECLARED, PUBLIC }
    private data class ContractValue(val type: Class<*>, val value: Optional<Any>)
    private class Signature(val parameters: Array<out Class<*>?>, val member: Method?) {
        fun matches(other: Array<out Class<*>?>): Boolean {
            if (parameters.size != other.size) return false
            for (i in parameters.indices) if (parameters[i] !== other[i]) return false
            return true
        }
    }
    private class MethodIndex {
        // Published arrays/entries are immutable. Only cold additions lock this index.
        val names = ConcurrentHashMap<String, Array<Signature>>()
        fun find(name: String, parameters: Array<out Class<*>?>): Signature? {
            val entries = names[name] ?: return null
            for (entry in entries) if (entry.matches(parameters)) return entry
            return null
        }
        inline fun get(name: String, parameters: Array<out Class<*>?>, resolve: (Array<out Class<*>?>) -> Method?): Method? {
            find(name, parameters)?.let { return it.member }
            synchronized(this) {
                find(name, parameters)?.let { return it.member }
                val signature = parameters.copyOf()
                val entry = Signature(signature, resolve(signature))
                names[name] = (names[name] ?: emptyArray()) + entry
                return entry.member
            }
        }
    }
    private class Members {
        val fields = ConcurrentHashMap<String, Optional<Field>>()
        val methods = MethodIndex()
        // Fixed lookup/accessibility policies select a table rather than allocating a key.
        private val exactFields = java.util.concurrent.atomic.AtomicReferenceArray<ConcurrentHashMap<String, Optional<Field>>>(5)
        private val exactMethods = java.util.concurrent.atomic.AtomicReferenceArray<MethodIndex>(4)
        fun fields(policy: Int): ConcurrentHashMap<String, Optional<Field>> {
            exactFields.get(policy)?.let { return it }
            return synchronized(this) {
                exactFields.get(policy) ?: ConcurrentHashMap<String, Optional<Field>>().also { exactFields.set(policy, it) }
            }
        }
        fun methods(policy: Int): MethodIndex {
            exactMethods.get(policy)?.let { return it }
            return synchronized(this) {
                exactMethods.get(policy) ?: MethodIndex().also { exactMethods.set(policy, it) }
            }
        }
        val contracts = ConcurrentHashMap<String, ContractValue>()
    }
    private fun policy(lookup: Lookup, accessible: Boolean): Int = lookup.ordinal * 2 + if (accessible) 1 else 0
    // ClassValue follows the actual defining loader and cannot pin it through a global Class map.
    private val members = object : ClassValue<Members>() {
        override fun computeValue(type: Class<*>): Members = Members()
    }
    /** Cache both present and absent members by their defining Class/loader. */
    fun field(type: Class<*>, name: String): java.lang.reflect.Field? {
        val fields = members.get(type).fields
        fields[name]?.let { return it.orElse(null) }
        return fields.computeIfAbsent(name) {
            java.util.Optional.ofNullable(generateSequence(type) { it.superclass }
                .mapNotNull { current -> runCatching { current.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }
                .firstOrNull())
        }.orElse(null)
    }

    fun read(owner: Any, name: String): Any? = field(owner.javaClass, name)?.get(owner)

    /** Legacy throwing lookup: only a genuine absence is cached; access failures retry. */
    fun requireInheritedField(type: Class<*>, name: String): Field {
        val fields = members.get(type).fields(REQUIRED_INHERITED_FIELD_POLICY)
        val cached = fields[name] ?: fields.computeIfAbsent(name) {
            var current: Class<*>? = type
            var found: Field? = null
            while (current != null) {
                try {
                    found = current.getDeclaredField(name).apply { isAccessible = true }
                    break
                } catch (_: NoSuchFieldException) { current = current.superclass }
            }
            Optional.ofNullable(found)
        }
        return cached.orElse(null) ?: throw NoSuchFieldException("${type.name}.$name")
    }

    /** Inherited-table handles are always accessible=true. Never share them with exactMethods,
     * whose handles retain the explicitly requested access policy. */
    fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method? = methodArray(type, name, parameters)
    fun method(type: Class<*>, name: String): Method? = methodArray(type, name, noParameters)
    fun methodArray(type: Class<*>, name: String, parameters: Array<out Class<*>>): Method? =
        members.get(type).methods.get(name, parameters) { signature ->
            generateSequence(type) { it.superclass }
                .mapNotNull { current -> runCatching { current.getDeclaredMethod(name, *signature).apply { isAccessible = true } }.getOrNull() }
                .firstOrNull()
                ?: runCatching { type.getMethod(name, *signature).apply { isAccessible = true } }.getOrNull()
        }

    fun method(owner: Any, name: String, vararg parameters: Class<*>): Method? =
        methodArray(owner.javaClass, name, parameters)

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

    private fun exactField(type: Class<*>, name: String, lookup: Lookup, accessible: Boolean = false): java.lang.reflect.Field? {
        val fields = members.get(type).fields(policy(lookup, accessible))
        fields[name]?.let { return it.orElse(null) }
        return fields.computeIfAbsent(name) {
            java.util.Optional.ofNullable(try {
                (if (lookup == Lookup.DECLARED) type.getDeclaredField(name) else type.getField(name)).apply { if (accessible) isAccessible = true }
            } catch (_: NoSuchFieldException) { null })
        }.orElse(null)
    }

    private fun exactMethod(type: Class<*>, name: String, parameters: Array<out Class<*>?>, lookup: Lookup, accessible: Boolean = false): Method? =
        members.get(type).methods(policy(lookup, accessible)).get(name, parameters) { signature ->
            try {
                (if (lookup == Lookup.DECLARED) type.getDeclaredMethod(name, *signature) else type.getMethod(name, *signature)).apply { if (accessible) isAccessible = true }
            } catch (_: NoSuchMethodException) { null }
        }

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

    fun declaredMethodArray(type: Class<*>, name: String, parameters: Array<out Class<*>>, accessible: Boolean) =
        exactMethod(type, name, parameters, Lookup.DECLARED, accessible)
    fun publicMethodArray(type: Class<*>, name: String, parameters: Array<out Class<*>>, accessible: Boolean) =
        exactMethod(type, name, parameters, Lookup.PUBLIC, accessible)
    fun requireDeclaredMethodArray(type: Class<*>, name: String, parameters: Array<out Class<*>?>, accessible: Boolean): Method =
        exactMethod(type, name, parameters, Lookup.DECLARED, accessible) ?: throw NoSuchMethodException("${type.name}#$name")
    fun requirePublicMethodArray(type: Class<*>, name: String, parameters: Array<out Class<*>?>, accessible: Boolean): Method =
        exactMethod(type, name, parameters, Lookup.PUBLIC, accessible) ?: throw NoSuchMethodException("${type.name}#$name")
    fun requirePublicMethodArray(type: Class<*>, name: String, parameters: Array<out Class<*>?>): Method =
        exactMethod(type, name, parameters, Lookup.PUBLIC, true) ?: throw NoSuchMethodException("${type.name}.$name")
}
