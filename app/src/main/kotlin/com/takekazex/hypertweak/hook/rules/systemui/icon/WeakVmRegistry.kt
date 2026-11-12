package com.takekazex.hypertweak.hook.rules.systemui.icon

import java.util.WeakHashMap

/** Values must not retain their keys; snapshots hold them only for the duration of a pass. */
internal class WeakVmRegistry<K : Any, V : Any> {
    private val entries = WeakHashMap<K, V>()
    @Synchronized fun get(key: K): V? = entries[key]
    @Synchronized fun getOrPut(key: K, create: () -> V): V = entries.getOrPut(key, create)
    @Synchronized fun snapshot(): List<Pair<K, V>> = entries.entries.map { it.key to it.value }
}

/** Rejects callbacks queued by an earlier binding or retired generation. */
internal class VmLease {
    private var generation = 0L
    fun renew(): Long = ++generation
    fun isCurrent(ticket: Long): Boolean = ticket == generation
}
