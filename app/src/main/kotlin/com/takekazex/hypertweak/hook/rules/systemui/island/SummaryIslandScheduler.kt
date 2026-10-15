package com.takekazex.hypertweak.hook.rules.systemui.island

/** Task admission and gesture decisions share one visibility policy, independent of slot count. */
internal class SummaryIslandScheduler {
    private val hidden = LinkedHashSet<String>()
    val hasHidden: Boolean get() = hidden.isNotEmpty()
    data class Change(val visible: List<String>, val task: String, val reveal: Boolean, val displaced: String? = null)

    fun isHidden(key: String?): Boolean = key != null && key in hidden

    fun plan(visible: List<String>, queued: List<String>, reveal: Boolean, downKey: String?, capacity: Int = 3): Change? {
        if (reveal) {
            // Prefer tasks still waiting for a turn over tasks explicitly hidden by the user.
            val next = queued.firstOrNull { it !in visible && it !in hidden }
                ?: hidden.firstOrNull { it in queued && it !in visible } ?: return null
            if (visible.size >= capacity.coerceAtLeast(2)) {
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

    data class Selection(val native: List<String>, val extras: List<String>) {
        val extra: String? get() = extras.firstOrNull()
        val visible: List<String> get() = native + extras
    }

    /** Fresh tasks can always use free capacity; only explicitly hidden identities are excluded. */
    fun select(visible: List<String>, queued: List<String>, smallEligible: Set<String>, capacity: Int = 3): Selection {
        val eligible = (visible + queued).distinct().filterNot(::isHidden)
        return Selection(eligible.take(2), eligible.drop(2).filter { it in smallEligible }.take((capacity - 2).coerceAtLeast(0)))
    }

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

    /** Explicitly expanding capacity starts a new presentation of all currently queued tasks. */
    fun capacityChanged(previousMore: Boolean, more: Boolean) {
        if (more && !previousMore) hidden.clear()
    }

    fun retain(keys: Set<String>) { hidden.retainAll(keys) }
    fun clear() { hidden.clear() }
}
