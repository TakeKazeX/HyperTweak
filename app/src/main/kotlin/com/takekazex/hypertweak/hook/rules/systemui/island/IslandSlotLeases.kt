package com.takekazex.hypertweak.hook.rules.systemui.island

/** Extra native SmallIsland views lent back before each host scheduling transaction. */
internal class IslandSlotLeases<T : Any> {
    private val values = ArrayList<T>()
    val tasks: List<T> get() = values.toList()
    fun stage(tasks: List<T>) { values.clear(); values.addAll(tasks.distinct()) }
    fun lend(queue: MutableList<T>) {
        values.asReversed().forEach { if (it !in queue) queue.add(0, it) }
        values.clear()
    }
    fun take(queue: MutableList<T>, tasks: List<T>) {
        check(tasks.distinct().size == tasks.size && tasks.all { it in queue }) { "Island lease is no longer in the native queue" }
        queue.removeAll(tasks.toSet())
        stage(tasks)
    }
    fun exchange(target: T, nativeSmall: T): T {
        val index = values.indexOf(target)
        check(index >= 0 && nativeSmall !in values)
        values[index] = nativeSmall
        return target
    }
}
