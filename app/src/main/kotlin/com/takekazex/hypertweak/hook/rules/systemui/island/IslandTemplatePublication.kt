package com.takekazex.hypertweak.hook.rules.systemui.island

/** Only the native template width transaction uses setTo; state/gesture animations keep to(). */
internal class IslandTemplatePublication {
    private val depth = ThreadLocal<Int>()
    val active: Boolean get() = (depth.get() ?: 0) > 0
    fun <T> run(block: () -> T): T {
        val previous = depth.get()
        depth.set((previous ?: 0) + 1)
        try { return block() } finally {
            if (previous == null) depth.remove() else depth.set(previous)
        }
    }
}
