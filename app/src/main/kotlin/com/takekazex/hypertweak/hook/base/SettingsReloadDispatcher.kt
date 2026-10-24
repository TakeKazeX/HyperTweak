package com.takekazex.hypertweak.hook.base

/** Main-thread confined; scheduler injection keeps lifecycle and retry behavior testable. */
internal class SettingsReloadDispatcher(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val failure: (String, Throwable?) -> Unit
) {
    private class Entry(val apply: (Set<String>) -> Boolean, val retryLimit: Int) { var retries = 0 }
    private val entries = linkedMapOf<String, Entry>()
    // null is a full-domain refresh; keyed changes must never narrow a pending full refresh.
    private val pending = linkedMapOf<String, Set<String>?>()
    private var active = true
    private val task = Runnable { drain() }

    fun register(id: String, apply: (Set<String>) -> Boolean) {
        registerWithRetryBudget(id, 4, apply)
    }

    /** Slow asynchronous owners report success only after publication, within a finite budget. */
    fun registerWithRetryBudget(id: String, retryLimit: Int, apply: (Set<String>) -> Boolean) {
        require(retryLimit in 0..120)
        check(id !in entries) { "duplicate settings reload participant: $id" }
        entries[id] = Entry(apply, retryLimit)
    }

    fun request(ids: Set<String>) = request(ids, emptySet())

    /** [keys] are the committed keys whose values changed; they are passed to the participants. */
    fun request(ids: Set<String>, keys: Set<String>) {
        if (!active) return
        ids.filter(entries::containsKey).forEach { id ->
            pending[id] = if (keys.isEmpty() || id in pending && pending[id] == null) null
                else pending[id].orEmpty() + keys
            entries.getValue(id).retries = 0
        }
        if (pending.isNotEmpty()) { remove(task); post(task, 150L) }
    }
    private fun drain() {
        if (!active) return
        val work = pending.toList()
        pending.clear()
        work.forEach { (id, keys) ->
            val entry = entries.getValue(id)
            try {
                if (!entry.apply(keys.orEmpty())) {
                    if (entry.retries++ < entry.retryLimit) {
                        pending[id] = if (keys == null || id in pending && pending[id] == null) null
                            else pending[id].orEmpty() + keys
                    } else failure(id, null)
                }
            } catch (error: Throwable) { failure(id, error) }
        }
        if (pending.isNotEmpty()) post(task, 250L)
    }
    fun cancel() { active = false; remove(task); pending.clear() }
    fun resume() { active = true; request(entries.keys) }
}
