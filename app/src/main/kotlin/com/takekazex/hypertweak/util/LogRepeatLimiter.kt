package com.takekazex.hypertweak.util

/** Bounded per-process suppression, with periodic counts instead of silent loss of repeats. */
class LogRepeatLimiter(private val windowMs: Long = 60000, private val capacity: Int = 256) {
    private data class State(val sample: LogRecord, val since: Long, var count: Int = 0)
    private val states = LinkedHashMap<List<String>, State>()

    @Synchronized
    fun accept(record: LogRecord, now: Long): Boolean {
        val key = listOf(record.level, record.scope, record.message, record.stack)
        val state = states[key]
        if (state != null) { state.count++; return false }
        // Do not suppress a new issue when the table is full. The periodic drain frees it.
        if (states.size < capacity) states[key] = State(record, now)
        return true
    }

    @Synchronized
    fun drain(now: Long, force: Boolean = false): List<LogRecord> {
        val summaries = mutableListOf<LogRecord>()
        val iterator = states.values.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            if (!force && now - state.since < windowMs) continue
            if (state.count > 0) summaries += state.sample.copy(
                event = "REPEATED", message = "REPEATED count=${state.count} additional occurrences: ${state.sample.message}",
                stack = ""
            )
            iterator.remove()
        }
        return summaries
    }
}
