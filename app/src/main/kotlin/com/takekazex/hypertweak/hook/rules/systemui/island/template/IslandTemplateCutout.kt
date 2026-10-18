package com.takekazex.hypertweak.hook.rules.systemui.island.template

import java.util.WeakHashMap

/** Exact template ownership survives delayed native callbacks; values never retain the parent. */
internal class TemplateCutoutRegistry {
    private val owners = WeakHashMap<Any, MutableMap<Any, Int>>()
    @Synchronized fun acquire(parent: Any, token: Any, width: Int) {
        require(width >= 0)
        owners.getOrPut(parent) { LinkedHashMap() }[token] = width
    }
    @Synchronized fun release(parent: Any, token: Any) {
        owners[parent]?.let { leases ->
            leases.remove(token)
            if (leases.isEmpty()) owners.remove(parent)
        }
    }
    @Synchronized fun clear() = owners.clear()
    @Synchronized fun width(parent: Any): Int? = owners[parent]?.values?.distinct()?.singleOrNull()
}

internal object IslandTemplateCutout {
    private val registry = TemplateCutoutRegistry()
    fun acquire(parent: Any, token: Any, width: Int) = registry.acquire(parent, token, width)
    fun release(parent: Any, token: Any) = registry.release(parent, token)
    fun clear() = registry.clear()
    fun width(parent: Any): Int? = registry.width(parent)
}
