package com.takekazex.hypertweak.hook.rules.systemui.icon

/**
 * Ordered fan-out for one host hook.
 *
 * Dispatch runs on host threads, often per frame, so it reads an immutable sorted snapshot and
 * allocates nothing. Closing a subscription takes effect at once, even for a dispatch that is
 * already iterating, and one failing callback never stops the others.
 */
internal class HostCallbackRegistry<P>(private val failed: (Throwable) -> Unit) {
    private class Entry<P>(val order: Int, val key: String, val action: (P) -> Unit) {
        @Volatile var active = true
    }

    private val lock = Any()
    @Volatile private var entries: List<Entry<P>> = emptyList()

    fun subscribe(order: Int, key: String, action: (P) -> Unit): AutoCloseable {
        val entry = Entry(order, key, action)
        synchronized(lock) {
            entries = (entries + entry).sortedWith(compareBy<Entry<P>>({ it.order }, { it.key }))
        }
        return AutoCloseable {
            entry.active = false
            synchronized(lock) { entries = entries - entry }
        }
    }

    fun dispatch(param: P) {
        val snapshot = entries
        for (index in snapshot.indices) {
            val entry = snapshot[index]
            if (!entry.active) continue
            try {
                entry.action(param)
            } catch (error: Throwable) {
                failed(error)
            }
        }
    }

    fun clear() = synchronized(lock) {
        entries.forEach { it.active = false }
        entries = emptyList()
    }
}
