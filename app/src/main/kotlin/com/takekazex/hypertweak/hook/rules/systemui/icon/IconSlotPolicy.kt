package com.takekazex.hypertweak.hook.rules.systemui.icon

/** Container-local overlays stay separate from the host/user's ignored-slot baseline. */
internal class IconMaskOwners {
    enum class Owner { DUO, CARRIER }
    private val containers = java.util.WeakHashMap<Any, MutableMap<Owner, Set<String>>>()

    fun owned(container: Any, owner: Owner): Set<String> = containers[container]?.get(owner).orEmpty()

    fun set(container: Any, owner: Owner, slots: Set<String>) {
        if (slots.isEmpty()) {
            containers[container]?.let { owners ->
                owners.remove(owner)
                if (owners.isEmpty()) containers.remove(container)
            }
        } else containers.getOrPut(container) { LinkedHashMap() }[owner] = slots.toSet()
    }

    fun slots(container: Any): List<String> = Owner.entries.flatMap { owned(container, it) }.distinct()
    fun merged(container: Any, baseline: List<String>): List<String> = (baseline + slots(container)).distinct()
    fun clear() = containers.clear()
}
