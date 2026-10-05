package com.takekazex.hypertweak.hook.rules.downloads

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object DownloadDetailContract {
    /** Helper methods with the same prototype are irrelevant unless semantic evidence names them. */
    fun renderer(owner: Class<*>, evidence: Collection<Method>, acceptsInfo: (Class<*>) -> Boolean): Method? =
        evidence.distinct().filter {
            it.declaringClass == owner && !Modifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE && infoIndex(it, acceptsInfo) != null
        }.singleOrNull()?.apply { isAccessible = true }

    fun infoIndex(method: Method, acceptsInfo: (Class<*>) -> Boolean): Int? =
        method.parameterTypes.mapIndexedNotNull { index, type -> index.takeIf { acceptsInfo(type) } }.singleOrNull()

    /** The field must feed the host's copy action, not merely contain something resembling a URL. */
    fun source(model: Class<*>, evidence: Collection<Field>): Field? = evidence.distinct().filter {
        it.declaringClass.isAssignableFrom(model) && it.type == String::class.java && !Modifier.isStatic(it.modifiers)
    }.singleOrNull()?.apply { isAccessible = true }
}
