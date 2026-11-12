package com.takekazex.hypertweak.hook.rules.systemui.icon

/** Main-thread arbitration. Revoke/restore the old writer before the new one captures opacity. */
internal class GlyphMotionLease(private val failed: (Throwable) -> Unit) {
    private var owner: Any? = null
    private var priority = Int.MIN_VALUE
    private var revoked: (() -> Unit)? = null

    fun acquire(next: Any, rank: Int, onRevoked: () -> Unit): Boolean {
        if (owner === next) return true
        if (owner != null && rank <= priority) return false
        retire()
        owner = next
        priority = rank
        revoked = onRevoked
        return true
    }

    fun release(previous: Any) {
        if (owner !== previous) return
        owner = null
        priority = Int.MIN_VALUE
        revoked = null
    }

    fun retire() {
        val restore = revoked
        owner = null
        priority = Int.MIN_VALUE
        revoked = null
        if (restore != null) runCatching(restore).onFailure(failed)
    }

    companion object {
        const val LEFT_OR_NATIVE = 10
        const val DUO = 20
    }
}
