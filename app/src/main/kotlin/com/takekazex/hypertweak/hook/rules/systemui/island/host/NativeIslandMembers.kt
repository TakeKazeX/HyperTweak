package com.takekazex.hypertweak.hook.rules.systemui.island.host

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Resolve-once reflection helpers shared by the island host discovery and contracts. */
internal object NativeIslandMembers {
    fun widthTween(loader: ClassLoader, descriptor: String): Method {
        val owner = descriptor.substringBefore(";->").removePrefix("L").replace('/', '.')
        val name = descriptor.substringAfter(";->").substringBefore('(')
        return loader.loadClass(owner).declaredMethods.single { method ->
            method.name == name && NativeIslandAnchors.matches("widthTween",
                method.parameterTypes.map(Class<*>::getName), method.returnType.name)
        }.apply { isAccessible = true }
    }

    fun named(owner: Class<*>, name: String, parameters: List<Class<*>>): Method =
        generateSequence(owner) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
            .filter { it.name == name && it.parameterTypes.toList() == parameters && !Modifier.isStatic(it.modifiers) }
            .distinctBy { it.parameterTypes.toList() }.single().apply { isAccessible = true }

    /** Kotlin default-argument bridges are static synthetic members on the declaring class. */
    fun namedStatic(owner: Class<*>, name: String, parameters: List<Class<*>>): Method =
        generateSequence(owner) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
            .filter { it.name == name && it.parameterTypes.toList() == parameters && Modifier.isStatic(it.modifiers) }
            .distinctBy { it.parameterTypes.toList() }.single().apply { isAccessible = true }

    /** The single void `(transition, String tag, callback-type enum)` dispatcher of an animation delegate. */
    fun lifecycle(owner: Class<*>): Method = owner.declaredMethods.filter { method ->
        val types = method.parameterTypes
        !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE && types.size == 3 &&
            types[1] == String::class.java && types[2].isEnum &&
            types[2].enumConstants.orEmpty().map { (it as Enum<*>).name }.containsAll(LIFECYCLE)
    }.single().apply { isAccessible = true }

    /** The single instance writer that stores the host's maximum, clock and battery widths together. */
    fun statusWidths(owner: Class<*>): Method = owner.declaredMethods.filter { method ->
        val float = java.lang.Float.TYPE
        !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
            method.parameterTypes.toList() == listOf(float, float, float)
    }.single().apply { isAccessible = true }

    private val LIFECYCLE = listOf("ANIM_START", "ANIM_FINISH", "ANIM_CANCEL")

    fun field(owner: Class<*>, type: Class<*>) = owner.declaredFields
        .filter { it.type == type && !Modifier.isStatic(it.modifiers) }.single().apply { isAccessible = true }
}
