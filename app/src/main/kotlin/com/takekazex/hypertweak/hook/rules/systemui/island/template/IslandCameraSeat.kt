package com.takekazex.hypertweak.hook.rules.systemui.island.template

import java.util.WeakHashMap

/**
 * Session-independent camera-seat ownership keyed by the native area parent.
 *
 * Native template callbacks (real and fake Folme onBegin) resolve their view without our
 * session map, so the area parent itself must carry the state until every overlapping lease
 * is released. Weak keys keep an abandoned host view tree from being retained here.
 */
internal class CameraSeatRegistry {
    private val counts = WeakHashMap<Any, Int>()

    @Synchronized fun acquire(key: Any) {
        counts[key] = (counts[key] ?: 0) + 1
    }

    @Synchronized fun release(key: Any) {
        val current = counts[key] ?: return
        if (current <= 1) counts.remove(key) else counts[key] = current - 1
    }

    @Synchronized fun holds(key: Any): Boolean = (counts[key] ?: 0) > 0

    @Synchronized fun clear() = counts.clear()

    @Synchronized fun tracked(): Int = counts.size
}

/** Process-wide registry shared by every hook generation and both real/fake template owners. */
internal object IslandCameraSeat {
    private val registry = CameraSeatRegistry()
    fun acquire(parent: Any) = registry.acquire(parent)
    fun release(parent: Any) = registry.release(parent)
    fun holds(parent: Any): Boolean = registry.holds(parent)
    fun clear() = registry.clear()
    fun tracked(): Int = registry.tracked()
}
