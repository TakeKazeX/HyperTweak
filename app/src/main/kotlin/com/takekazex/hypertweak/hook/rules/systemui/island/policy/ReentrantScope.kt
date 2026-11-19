package com.takekazex.hypertweak.hook.rules.systemui.island.policy

/**
 * Re-entrant, thread-confined flag: [active] is true while any [run] on this thread is executing.
 * The session holds one per concern, e.g. explicit relayout publication or a native cutout read.
 */
internal class ReentrantScope {
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
