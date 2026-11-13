package com.takekazex.hypertweak.hook.rules.systemui.icon

/** Sibling branches that can be hidden while preserving the complete boundary-to-label path. */
internal object TypeOnlyBranchPolicy {
    fun <T : Any> hiddenBranches(boundary: T, label: T, parent: (T) -> T?, children: (T) -> List<T>): Set<T>? {
        val path = ArrayList<T>()
        var current: T? = label
        while (current !== boundary) {
            path += current ?: return null
            current = parent(current)
        }
        path += boundary
        return buildSet {
            for (index in 1 until path.size) {
                children(path[index]).filterTo(this) { it !== path[index - 1] }
            }
        }
    }
}
