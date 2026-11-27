package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import java.util.WeakHashMap

/**
 * Manager -> [LeftState], weak on the manager. Framework View visibility/alpha routes consult the
 * states for every ViewGroup write in SystemUI, many per animation frame, so they read a cached
 * array instead of walking the hash table. Mutations drop the cache; a size change (stale keys
 * expunged by GC) rebuilds it. Callers hold the map's monitor, as for every other access.
 */
internal class LeftStateMap : WeakHashMap<Any, LeftState>() {
    private var cached: Array<LeftState>? = null

    override fun put(key: Any, value: LeftState): LeftState? = super.put(key, value).also { cached = null }
    override fun putAll(from: Map<out Any, LeftState>) { super.putAll(from); cached = null }
    override fun remove(key: Any?): LeftState? = super.remove(key).also { cached = null }
    override fun clear() { super.clear(); cached = null }

    fun snapshot(): Array<LeftState> {
        val current = cached
        if (current != null && current.size == size) return current
        return values.toTypedArray().also { cached = it }
    }
}
