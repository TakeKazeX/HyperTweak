package com.takekazex.hypertweak.hook.rules.systemui.island

/** Preserve native queue order while lending the visible third task back for host scheduling. */
internal class ThirdIslandSlot<T : Any> {
    var task: T? = null

    fun lend(queue: MutableList<T>): T? {
        val previous = task ?: return null
        if (previous !in queue) queue.add(0, previous)
        task = null
        return previous
    }

    fun take(queue: MutableList<T>, selected: T) {
        check(queue.remove(selected)) { "Third island is no longer in the host queue" }
        task = selected
    }

    /** Returns the task becoming the native small slot; physical positions stay unchanged. */
    fun exchange(nativeSmall: T): T {
        val previous = checkNotNull(task)
        task = nativeSmall
        return previous
    }
}
