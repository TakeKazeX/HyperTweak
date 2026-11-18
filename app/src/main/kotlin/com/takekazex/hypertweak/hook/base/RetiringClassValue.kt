package com.takekazex.hypertweak.hook.base

import java.util.Collections
import java.util.WeakHashMap

/**
 * A [ClassValue] whose entries are removed from the host classes when its generation retires.
 *
 * ART stores a ClassValue entry inside the keyed `Class`, and the entry holds its value strongly.
 * A value defined by the module keeps the module class loader, with all of its DEX data, alive for
 * as long as the host class lives, which for host and framework classes is the process lifetime.
 * Every hot reload would otherwise leave the retired generation's loader behind.
 */
internal class RetiringClassValue<T : Any>(private val compute: (Class<*>) -> T) {
    @Volatile private var retired = false
    // Weak keys: tracking must not pin a plugin class whose loader the host unloads.
    private val types: MutableSet<Class<*>> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>()))
    private val values = object : ClassValue<T>() {
        override fun computeValue(type: Class<*>): T {
            types.add(type)
            return compute(type)
        }
    }

    fun get(type: Class<*>): T {
        if (retired) return compute(type)
        val value = values.get(type)
        // A computation that raced retirement must not leave its entry in the host class.
        if (retired) values.remove(type)
        return value
    }

    /** Idempotent. Later reads still compute, without caching anything in host classes. */
    fun retire() {
        retired = true
        val snapshot = synchronized(types) { types.toList().also { types.clear() } }
        snapshot.forEach { values.remove(it) }
    }
}
