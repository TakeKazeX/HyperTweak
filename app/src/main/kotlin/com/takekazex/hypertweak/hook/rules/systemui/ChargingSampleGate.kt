package com.takekazex.hypertweak.hook.rules.systemui

/** Main-thread ownership for a single asynchronous read, including stop/restart races. */
internal class ChargingSampleGate {
    private var generation = 0L
    private var active = false
    private var pending: Long? = null
    private var lastRequested: Long? = null

    fun start() {
        if (active) return
        active = true
        generation++
        lastRequested = null
    }

    fun stop() {
        active = false
        generation++
        lastRequested = null
        // Keep the in-flight slot until the worker returns, even across a new owner.
    }

    fun request(now: Long, interval: Long): Long? {
        if (!active || pending != null || lastRequested?.let { now - it < interval } == true) return null
        return generation.also { pending = it; lastRequested = now }
    }

    fun complete(ticket: Long): Boolean {
        if (pending != ticket) return false
        pending = null
        if (!active || ticket != generation) return false
        return true
    }
}
