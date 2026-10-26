package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import com.takekazex.hypertweak.hook.rules.systemui.icon.SignalTargetOwnership

/** Carrier target arbitration. Capture one row per operation; no per-frame adapter allocation. */
internal class CarrierTargetLeases<T : Any, P : Any>(private val host: Host<T, P>) {
    interface Host<T, P> {
        fun parts(target: T): P?
        fun ready(target: T, parts: P): Boolean
        fun usable(target: T): Boolean
        fun network(target: T, parts: P): Boolean
        fun cellularReady(parts: P): Boolean
        fun stopMotion(target: T)
        fun fadeLease(target: T, parts: P, leased: Boolean)
        fun alpha(target: T, value: Float)
    }
    private val owners = SignalTargetOwnership<T>()
    operator fun contains(target: T) = target in owners
    fun clear() = owners.clear()
    fun retire(target: T) = owners.retire(target)
    fun acquire(target: T, owner: Any): Boolean {
        val parts = host.parts(target) ?: return false
        if (!host.ready(target, parts) || !host.usable(target) || (target in owners && !owners.owns(target, owner))) return false
        if (owners.owns(target, owner)) return true
        host.stopMotion(target)
        if (host.network(target, parts)) host.fadeLease(target, parts, true)
        host.alpha(target, 1f)
        check(owners.acquire(target, owner))
        return true
    }
    fun release(target: T, owner: Any) {
        if (!owners.release(target, owner)) return
        val parts = host.parts(target)
        if (parts != null && host.network(target, parts)) host.fadeLease(target, parts, false)
        else host.alpha(target, if (parts != null && host.cellularReady(parts)) 1f else 0f)
    }
}
