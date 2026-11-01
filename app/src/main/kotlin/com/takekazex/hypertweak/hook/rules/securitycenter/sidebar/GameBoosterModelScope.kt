package com.takekazex.hypertweak.hook.rules.securitycenter.sidebar

/** Nesting and thread isolation keep unrelated model consumers native, including after exceptions. */
internal class GameBoosterModelScope {
    private val depth = ThreadLocal<Int>()
    fun enter() { depth.set((depth.get() ?: 0) + 1) }
    fun leave() {
        val remaining = (depth.get() ?: 0) - 1
        if (remaining <= 0) depth.remove() else depth.set(remaining)
    }
    fun bypass(enabled: Boolean) = enabled && (depth.get() ?: 0) > 0
}
