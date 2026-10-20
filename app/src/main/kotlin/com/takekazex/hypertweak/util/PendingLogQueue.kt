package com.takekazex.hypertweak.util

/** Independent bounded rings: diagnostic floods cannot evict warnings or errors. */
internal class PendingLogQueue<T>(
    private val capacity: Int = 512,
    private val maxCharsPerLevel: Int = 512 * 1024
) {
    private data class Entry<T>(val value: T, val chars: Int)
    private class Ring<T> {
        val entries = ArrayDeque<Entry<T>>()
        var chars = 0
        var dropped = 0L
    }
    private val order = listOf("E", "W", "I", "D")
    private val rings = order.associateWith { Ring<T>() }

    init { require(capacity > 0 && maxCharsPerLevel > 0) }

    /** Returns true when a drain should be expedited rather than waiting for the batch timer. */
    @Synchronized fun offer(level: String, value: T, chars: Int): Boolean {
        val ring = rings.getValue(when (level) { "F" -> "E"; "E", "W", "I" -> level; else -> "D" })
        val size = chars.coerceAtLeast(0)
        if (size > maxCharsPerLevel) {
            ring.dropped++
            return true
        }
        while (ring.entries.size >= capacity || ring.chars + size > maxCharsPerLevel) {
            ring.chars -= ring.entries.removeFirst().chars
            ring.dropped++
        }
        ring.entries.addLast(Entry(value, size))
        ring.chars += size
        return ring.entries.size >= capacity * 3 / 4 || ring.chars >= maxCharsPerLevel * 3 / 4
    }

    data class Batch<T>(val values: List<T>, val dropped: Map<String, Long>)

    /** Important records reach a slow sink first; FIFO is preserved within each severity. */
    @Synchronized fun drain(): Batch<T> {
        val values = ArrayList<T>()
        val dropped = LinkedHashMap<String, Long>()
        for (level in order) {
            val ring = rings.getValue(level)
            values.addAll(ring.entries.map { it.value })
            if (ring.dropped > 0) dropped[level] = ring.dropped
            ring.entries.clear()
            ring.chars = 0
            ring.dropped = 0
        }
        return Batch(values, dropped)
    }
}
