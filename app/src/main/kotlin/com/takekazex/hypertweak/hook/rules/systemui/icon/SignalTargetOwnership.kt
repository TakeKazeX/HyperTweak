package com.takekazex.hypertweak.hook.rules.systemui.icon

import java.util.IdentityHashMap

/** Main-thread confined leases. Target and owner equality must both be referential. */
internal class SignalTargetOwnership<T : Any> {
    private val owners = IdentityHashMap<T, Any>()

    operator fun contains(target: T): Boolean = owners.containsKey(target)
    fun owns(target: T, owner: Any): Boolean = owners[target] === owner
    fun acquire(target: T, owner: Any): Boolean {
        if (target in owners && !owns(target, owner)) return false
        owners[target] = owner
        return true
    }
    fun release(target: T, owner: Any): Boolean {
        if (!owns(target, owner)) return false
        owners.remove(target)
        return true
    }
    /** Host retirement/detach, rather than an arbitrary borrower, may invalidate a target. */
    fun retire(target: T) { owners.remove(target) }
    fun clear() { owners.clear() }
}
