package com.takekazex.hypertweak.hook.base

import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Module-owned replacements for the EzHookTool 1.3.1 `core` reflection helpers HyperTweak used.
 *
 * Each helper keeps the library's observable result. Method lookup for hooks is not here: hook
 * targets are selected as declared members through [HostSelect], which rejects ambiguity.
 * - [callMethodOrNull] walks the receiver's class chain (superclass private methods excluded,
 *   instance methods only), keeps the single most specific applicable overload, and returns null
 *   when none or several remain. Exceptions thrown by the invoked method propagate.
 * - [loadHostClassOrNull] uses `Class.forName(name, false, loader)`, then retries with the last
 *   `.` replaced by `$` for nested classes.
 */
/** All declared constructors, made accessible. */
fun Class<*>.declaredConstructorsAccessible(): List<Constructor<*>> =
    declaredConstructors.onEach { it.isAccessible = true }.toList()

fun loadHostClassOrNull(name: String, loader: ClassLoader?): Class<*>? =
    tryLoad(name, loader) ?: name.lastIndexOf('.').takeIf { it > 0 }?.let { index ->
        tryLoad(name.replaceRange(index, index + 1, "$"), loader)
    }

fun loadHostClass(name: String, loader: ClassLoader?): Class<*> =
    loadHostClassOrNull(name, loader) ?: throw ClassNotFoundException(name)

private fun tryLoad(name: String, loader: ClassLoader?): Class<*>? = try {
    Class.forName(name, false, loader)
} catch (_: ClassNotFoundException) {
    null
} catch (_: NoClassDefFoundError) {
    null
}

/**
 * Invokes the single most specific instance method [name] applicable to [args], searching the
 * receiver's class chain. Returns null when no method applies, several are equally specific, or
 * the call is rejected by reflection; an exception thrown by the method itself propagates.
 */
fun Any.callMethodOrNull(name: String, vararg args: Any?): Any? {
    val argTypes = args.map { it?.javaClass }
    val key = argTypes.joinToString(",", prefix = "call:$name(", postfix = ")") { it?.name ?: "null" }
    val method = HostMembers.cached(javaClass, key, Method::class.java) {
        bestInstanceMethod(javaClass, name, argTypes)
    } ?: return null
    return try {
        method.invoke(this, *args)
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    } catch (_: IllegalAccessException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private val WRAPPERS: Map<Class<*>, Class<*>> = mapOf(
    java.lang.Boolean.TYPE to java.lang.Boolean::class.java,
    java.lang.Byte.TYPE to java.lang.Byte::class.java,
    java.lang.Character.TYPE to java.lang.Character::class.java,
    java.lang.Short.TYPE to java.lang.Short::class.java,
    java.lang.Integer.TYPE to java.lang.Integer::class.java,
    java.lang.Long.TYPE to java.lang.Long::class.java,
    java.lang.Float.TYPE to java.lang.Float::class.java,
    java.lang.Double.TYPE to java.lang.Double::class.java,
    java.lang.Void.TYPE to java.lang.Void::class.java,
)

private fun isTypeMatch(expected: Class<*>, actual: Class<*>): Boolean =
    expected == actual ||
        (WRAPPERS[expected] ?: expected) == (WRAPPERS[actual] ?: actual) ||
        expected.isAssignableFrom(actual)

/** 0 exact, 1 boxing, 2 reference widening or null, -1 not applicable. */
internal fun matchRank(param: Class<*>, arg: Class<*>?): Int = when {
    arg == null -> if (param.isPrimitive) -1 else 2
    param == arg -> 0
    !isTypeMatch(param, arg) -> -1
    param.isPrimitive || arg.isPrimitive -> 1
    else -> 2
}

private fun moreSpecific(a: Method, b: Method, argTypes: List<Class<*>?>): Boolean {
    val pa = a.parameterTypes
    val pb = b.parameterTypes
    var better = false
    for (i in argTypes.indices) {
        val ra = matchRank(pa[i], argTypes[i])
        val rb = matchRank(pb[i], argTypes[i])
        if (ra > rb) return false
        if (ra < rb) {
            better = true
        } else if (pa[i] != pb[i]) {
            if (!pb[i].isAssignableFrom(pa[i])) return false
            better = true
        }
    }
    if (better) return true
    if (a.declaringClass != b.declaringClass) return b.declaringClass.isAssignableFrom(a.declaringClass)
    if (a.isBridge != b.isBridge) return !a.isBridge
    return a.returnType != b.returnType && b.returnType.isAssignableFrom(a.returnType)
}

internal fun bestInstanceMethod(owner: Class<*>, name: String, argTypes: List<Class<*>?>): Method? {
    val candidates = ArrayList<Method>()
    var current: Class<*>? = owner
    while (current != null) {
        for (method in current.declaredMethods) {
            if (method.name != name) continue
            if (current != owner && Modifier.isPrivate(method.modifiers)) continue
            if (Modifier.isStatic(method.modifiers)) continue
            candidates += method
        }
        current = current.superclass
    }
    val applicable = candidates.distinct().filter { method ->
        val params = method.parameterTypes
        params.size == argTypes.size && params.indices.all { matchRank(params[it], argTypes[it]) >= 0 }
    }
    val best = applicable.filter { candidate ->
        applicable.none { other -> other !== candidate && moreSpecific(other, candidate, argTypes) }
    }
    return best.singleOrNull()?.apply { isAccessible = true }
}
