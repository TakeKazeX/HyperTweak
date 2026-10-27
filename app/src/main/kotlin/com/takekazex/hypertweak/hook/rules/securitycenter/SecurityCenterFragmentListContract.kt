package com.takekazex.hypertweak.hook.rules.securitycenter

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Select the public FragmentManager list accessor without relying on an obfuscated name. */
internal object SecurityCenterFragmentListContract {
    fun resolve(type: Class<*>): Method? {
        val candidates = generateSequence(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 && it.returnType == List::class.java }
            .distinctBy { it.name }
            .toList()
        return (candidates.singleOrNull { it.name == "getFragments" } ?: candidates.singleOrNull())
            ?.apply { isAccessible = true }
    }

    fun validate(value: Any?, fragmentClass: Class<*>): List<Any>? {
        val fragments = (value as? List<*>)?.filterNotNull() ?: return null
        return fragments.takeIf { items -> items.all(fragmentClass::isInstance) }
    }
}
