package com.takekazex.hypertweak.hook.base

/** Main-thread confined; scheduler injection keeps lifecycle and retry behavior testable. */
internal class SettingsReloadDispatcher(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val failure: (String, Throwable?) -> Unit
) {
    private class Entry(val apply: () -> Boolean) { var retries = 0 }
    private val entries = linkedMapOf<String, Entry>()
    private val pending = linkedSetOf<String>()
    private var active = true
    private val task = Runnable { drain() }

    fun register(id: String, apply: () -> Boolean) {
        check(id !in entries) { "duplicate settings reload participant: $id" }
        entries[id] = Entry(apply)
    }
    fun request(ids: Set<String>) {
        if (!active) return
        ids.filter(entries::containsKey).forEach { id -> pending += id; entries.getValue(id).retries = 0 }
        if (pending.isNotEmpty()) { remove(task); post(task, 150L) }
    }
    private fun drain() {
        if (!active) return
        val work = pending.toList()
        pending.clear()
        work.forEach { id ->
            val entry = entries.getValue(id)
            try {
                if (!entry.apply()) {
                    if (entry.retries++ < 4) pending += id else failure(id, null)
                }
            } catch (error: Throwable) { failure(id, error) }
        }
        if (pending.isNotEmpty()) post(task, 250L)
    }
    fun cancel() { active = false; remove(task); pending.clear() }
    fun resume() { active = true; request(entries.keys) }
}
