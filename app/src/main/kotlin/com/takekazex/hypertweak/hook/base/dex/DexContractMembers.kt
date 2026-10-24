package com.takekazex.hypertweak.hook.base.dex

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Explicit role maps for final contracts. Reading members never opens a DEX source/backend. */
internal class DexContractMembers(private val values: Map<String, String>, private val loader: ClassLoader) {
    fun text(role: String): String = values.getValue(role)
    fun method(role: String): Method = requireNotNull(MethodDescriptorCodec.method(text(role), loader)) { "Invalid method role: $role" }
    fun field(role: String): Field = requireNotNull(MethodDescriptorCodec.field(text(role), loader)) { "Invalid field role: $role" }
    fun constructor(role: String): Constructor<*> = requireNotNull(MethodDescriptorCodec.constructor(text(role), loader)) { "Invalid constructor role: $role" }
    fun type(role: String): Class<*> = requireNotNull(MethodDescriptorCodec.type(text(role), loader))
    fun optionalType(role: String): Class<*>? = if (text(role).isEmpty()) null else type(role)
    fun optionalConstructor(role: String): Constructor<*>? = if (text(role).isEmpty()) null else constructor(role)
    fun optionalMethod(role: String): Method? = if (text(role).isEmpty()) null else method(role)
    fun methods(role: String): List<Method> = text(role).lineSequence().filter(String::isNotEmpty).map {
        requireNotNull(MethodDescriptorCodec.method(it, loader))
    }.toList()
    fun executables(role: String): List<Executable> = text(role).lineSequence().filter(String::isNotEmpty).map {
        if ("-><init>(" in it) requireNotNull(MethodDescriptorCodec.constructor(it, loader))
        else requireNotNull(MethodDescriptorCodec.method(it, loader))
    }.toList()
    companion object {
        fun encode(vararg roles: Pair<String, Any?>): Map<String, String> = roles.associate { it.first to encodeValue(it.second) }
        private fun encodeValue(value: Any?): String = when (value) {
            null -> ""
            is Method -> MethodDescriptorCodec.descriptor(value)
            is Constructor<*> -> MethodDescriptorCodec.descriptor(value)
            is Field -> MethodDescriptorCodec.descriptor(value)
            is Class<*> -> MethodDescriptorCodec.typeDescriptor(value)
            is Iterable<*> -> value.joinToString("\n") { encodeValue(it) }
            is String -> value
            else -> error("Unsupported DEX contract member: ${value.javaClass.name}")
        }
    }
}
