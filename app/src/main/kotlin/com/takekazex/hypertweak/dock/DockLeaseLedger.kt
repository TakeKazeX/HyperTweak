package com.takekazex.hypertweak.dock

/** Main-thread generation fence for asynchronous hosts; IDs alone cannot reject stale creates. */
internal class DockLeaseLedger {
    var active = 0L
        private set
    private var retired = 0L
    fun admit(lease: Long): Boolean {
        if (lease <= 0 || lease <= retired || lease < active) return false
        active = maxOf(active, lease)
        return true
    }
    fun retire(lease: Long) { retired = maxOf(retired, lease) }
}
