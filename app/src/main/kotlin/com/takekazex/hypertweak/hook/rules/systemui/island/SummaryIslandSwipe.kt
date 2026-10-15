package com.takekazex.hypertweak.hook.rules.systemui.island

/** One horizontal gesture changes one task, independently of the native two-slot carousel. */
internal class SummaryIslandSwipe {
    private val hidden = LinkedHashSet<String>()
    val hasHidden: Boolean get() = hidden.isNotEmpty()
    data class Change(val visible: List<String>, val task: String, val reveal: Boolean, val displaced: String? = null)

    fun isHidden(key: String?): Boolean = key != null && key in hidden

    fun plan(visible: List<String>, queued: List<String>, reveal: Boolean, downKey: String?): Change? {
        if (reveal) {
            // Prefer tasks still waiting for a turn over tasks explicitly hidden by the user.
            val next = queued.firstOrNull { it !in visible && it !in hidden }
                ?: hidden.firstOrNull { it in queued && it !in visible } ?: return null
            if (visible.size >= 3) {
                val previous = if (downKey == null) visible.first() else downKey.takeIf { it in visible } ?: return null
                return Change(visible.map { if (it == previous) next else it }, next, true, previous)
            }
            return Change(visible + next, next, true)
        }
        val target = if (downKey == null) visible.firstOrNull() else downKey.takeIf { it in visible }
        return target?.let {
            val backup = queued.firstOrNull { candidate -> candidate !in visible && candidate !in hidden }
            val after = if (backup == null) visible - it else visible.map { current -> if (current == it) backup else current }
            Change(after, it, false)
        }
    }

    fun commit(change: Change) {
        if (change.reveal) hidden.remove(change.task)
        val removed = if (change.reveal) change.displaced else change.task
        if (removed != null) {
            hidden.remove(removed)
            hidden.add(removed)
        }
    }

    fun reconcile(visible: List<String>, queued: List<String>, limit: Int): List<String> =
        (visible + queued).distinct().filterNot(::isHidden).take(limit.coerceIn(0, 3))

    /** Opening a summary card frees one seat, even if its backup was hidden by rotation. */
    fun fillExpandedVacancy(previousExpanded: String?, expanded: String?,
        previousVisible: List<String>, available: List<String>): String? {
        if (expanded == null || expanded == previousExpanded || expanded !in previousVisible) return null
        val remaining = previousVisible.filter { it != expanded && it in available }.toSet()
        val candidates = available.distinct().filter { it != expanded && it !in remaining }
        val next = candidates.firstOrNull { it !in hidden } ?: candidates.firstOrNull() ?: return null
        hidden.remove(next)
        return next
    }

    fun retain(keys: Set<String>) { hidden.retainAll(keys) }
    fun clear() { hidden.clear() }
}
