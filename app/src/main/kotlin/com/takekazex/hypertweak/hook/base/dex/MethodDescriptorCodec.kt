package com.takekazex.hypertweak.hook.base.dex

import java.lang.reflect.Method
import java.lang.reflect.Field

/**
 * Encodes a reflected method as `Lowner;->name(params)return` and back.
 *
 * The security-center privacy targets are cached as these descriptors: the cache is keyed by the
 * target APK build, so a descriptor only has to survive an app restart, not an APK update. Class
 * and method resolution therefore happens through the host loader when the cache is read; an entry
 * that no longer materializes invalidates the cache instead of installing a hook at a wrong site.
 */
internal object MethodDescriptorCodec {
    private val PRIMITIVES = mapOf(
        "void" to "V", "boolean" to "Z", "byte" to "B", "char" to "C", "short" to "S",
        "int" to "I", "long" to "J", "float" to "F", "double" to "D",
    )

    fun descriptor(method: org.jf.dexlib2.iface.reference.MethodReference): String =
        "${method.definingClass}->${method.name}(" + method.parameterTypes.joinToString("") + ")${method.returnType}"
    fun descriptor(field: org.jf.dexlib2.iface.reference.FieldReference): String =
        "${field.definingClass}->${field.name}:${field.type}"
    fun descriptor(constructor: java.lang.reflect.Constructor<*>): String =
        typeDescriptor(constructor.declaringClass) + "-><init>(" +
            constructor.parameterTypes.joinToString("") { typeDescriptor(it) } + ")V"
    fun type(value: String, loader: ClassLoader): Class<*>? = parseType(value, loader)
    fun constructor(descriptor: String, loader: ClassLoader): java.lang.reflect.Constructor<*>? = runCatching {
        val arrow = descriptor.indexOf("-><init>(")
        require(arrow > 0 && descriptor.endsWith(")V"))
        val owner = requireNotNull(classOf(descriptor.substring(0, arrow), loader))
        val parameters = requireNotNull(parseTypes(descriptor.substring(arrow + 9, descriptor.length - 2), loader))
        require(parameters.none { it == Void.TYPE })
        owner.getDeclaredConstructor(*parameters.toTypedArray()).apply { isAccessible = true }
    }.getOrNull()

    fun descriptor(method: Method): String = buildString {
        append(typeDescriptor(method.declaringClass))
        append("->")
        append(method.name)
        append('(')
        method.parameterTypes.forEach { append(typeDescriptor(it)) }
        append(')')
        append(typeDescriptor(method.returnType))
    }

    fun typeDescriptor(type: Class<*>): String = when {
        type.isArray -> type.name.replace('.', '/')
        type.isPrimitive -> PRIMITIVES.getValue(type.name)
        else -> "L${type.name.replace('.', '/')};"
    }

    fun descriptor(field: Field): String =
        "${typeDescriptor(field.declaringClass)}->${field.name}:${typeDescriptor(field.type)}"

    fun field(descriptor: String, loader: ClassLoader): Field? = runCatching {
        val arrow = descriptor.indexOf("->")
        val colon = descriptor.indexOf(':', arrow + 2)
        if (arrow <= 0 || colon <= arrow + 2) return null
        val owner = classOf(descriptor.substring(0, arrow), loader) ?: return null
        val type = parseType(descriptor.substring(colon + 1), loader) ?: return null
        owner.getDeclaredField(descriptor.substring(arrow + 2, colon))
            .takeIf { it.type == type }?.apply { isAccessible = true }
    }.getOrNull()

    fun method(descriptor: String, loader: ClassLoader): Method? = runCatching {
        val arrow = descriptor.indexOf("->")
        if (arrow <= 0) return null
        val owner = classOf(descriptor.substring(0, arrow), loader) ?: return null
        val rest = descriptor.substring(arrow + 2)
        val open = rest.indexOf('(')
        val close = rest.indexOf(')', open + 1)
        if (open <= 0 || close <= 0) return null
        val name = rest.substring(0, open)
        val parameters = parseTypes(rest.substring(open + 1, close), loader) ?: return null
        val returnType = parseType(rest.substring(close + 1), loader) ?: return null
        if (parameters.any { it == Void.TYPE }) return null
        val method = owner.getDeclaredMethod(name, *parameters.toTypedArray())
        if (typeDescriptor(method.returnType) != typeDescriptor(returnType)) return null
        method.isAccessible = true
        method
    }.getOrNull()

    /** Returns null when the descriptor is malformed, so a corrupt cache entry is dropped. */
    private fun parseTypes(value: String, loader: ClassLoader): List<Class<*>>? {
        val types = ArrayList<Class<*>>()
        var index = 0
        while (index < value.length) {
            val single = parseTypeAt(value, index, loader) ?: return null
            types += single.first
            index = single.second
        }
        return types
    }

    private fun parseType(value: String, loader: ClassLoader): Class<*>? =
        parseTypeAt(value, 0, loader)?.takeIf { it.second == value.length }?.first

    private fun parseTypeAt(value: String, start: Int, loader: ClassLoader): Pair<Class<*>, Int>? {
        if (start >= value.length) return null
        return when (value[start]) {
            '[' -> {
                var end = start
                while (end < value.length && value[end] == '[') end++
                if (end >= value.length) return null
                val element = parseTypeAt(value, end, loader) ?: return null
                val name = value.substring(start, element.second).replace('/', '.')
                runCatching { Class.forName(name, false, loader) }.getOrNull()?.let { it to element.second }
            }

            'L' -> {
                val end = value.indexOf(';', start)
                if (end < 0) return null
                classOf(value.substring(start, end + 1), loader)?.let { it to end + 1 }
            }

            in 'A'..'K', in 'M'..'Z', in 'a'..'z' -> PRIMITIVES.entries
                .firstOrNull { it.value == value[start].toString() }
                ?.let { primitiveType(it.key) to start + 1 }

            else -> null
        }
    }

    private fun classOf(descriptor: String, loader: ClassLoader): Class<*>? {
        if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) return null
        val name = descriptor.substring(1, descriptor.length - 1).replace('/', '.')
        return runCatching { Class.forName(name, false, loader) }.getOrNull()
    }

    private fun primitiveType(name: String): Class<*> = when (name) {
        "void" -> Void.TYPE
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        else -> error("unknown primitive $name")
    }
}
