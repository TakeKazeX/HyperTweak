package com.takekazex.hypertweak.presentation.icon

/** Bounded ownership only: eviction never disposes a value still borrowed by a host. */
internal class ByteBudgetCache<K, V>(
    private val maxEntries: Int,
    private val maxBytes: Long,
    private val bytes: (V) -> Long
) {
    private class Entry<V>(val value: V, val bytes: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, .75f, true)
    var retainedBytes = 0L
        private set
    val size get() = entries.size
    operator fun get(key: K): V? = entries[key]?.value
    operator fun set(key: K, value: V) {
        val weight = bytes(value).coerceAtLeast(0)
        entries.remove(key)?.let { retainedBytes -= it.bytes }
        if (weight > maxBytes) return
        entries[key] = Entry(value, weight)
        retainedBytes += weight
        val iterator = entries.entries.iterator()
        while (entries.size > maxEntries || retainedBytes > maxBytes) {
            retainedBytes -= iterator.next().value.bytes
            iterator.remove()
        }
    }
    fun clear() { entries.clear(); retainedBytes = 0 }
}
