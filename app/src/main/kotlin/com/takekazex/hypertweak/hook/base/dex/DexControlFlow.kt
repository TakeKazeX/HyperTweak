package com.takekazex.hypertweak.hook.base.dex

/** Mechanical graph traversal; all business evidence stays in feature contracts. */
internal object DexControlFlow {
    fun reachable(edges: Map<Int, List<Int>>, starts: Set<Int>, omit: Pair<Int, Int>? = null): Set<Int> {
        val pending = java.util.ArrayDeque(starts)
        val seen = LinkedHashSet<Int>()
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (!seen.add(node)) continue
            edges[node].orEmpty().filterNot { omit == (node to it) }.forEach(pending::add)
        }
        return seen
    }
    fun edgeDominates(edges: Map<Int, List<Int>>, roots: Set<Int>, from: Int, to: Int, target: Int): Boolean =
        target in reachable(edges, roots) && target !in reachable(edges, roots, from to to)
}
