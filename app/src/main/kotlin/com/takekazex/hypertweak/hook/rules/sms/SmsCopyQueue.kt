package com.takekazex.hypertweak.hook.rules.sms

/** Process-local admission and ordering. No message body/address is retained or persisted. */
internal class SmsCopyQueue {
    companion object {
        const val MAX_AGE_MS = 120_000L
        private const val MAX_SEEN = 128
    }
    data class Ticket(val messageId: Long, val order: Long, val epoch: Long,
        val elapsed: Long, val wallTime: Long)
    data class Pending(val messageId: Long, val order: Long, val elapsed: Long,
        val wallTime: Long, val code: String)

    private var enabled = false
    private var epoch = 0L
    private var sequence = 0L
    private var latestAccepted = 0L
    private val seen = linkedSetOf<Long>()
    private var pending: Pending? = null

    @Synchronized fun enable(value: Boolean) {
        if (enabled == value) return
        enabled = value
        epoch++
        pending = null
    }

    @Synchronized fun begin(messageId: Long, elapsed: Long, wallTime: Long): Ticket? =
        if (!enabled || messageId <= 0 || messageId in seen) null
        else Ticket(messageId, ++sequence, epoch, elapsed, wallTime)

    @Synchronized fun offer(ticket: Ticket, code: String, now: Long): Boolean {
        if (!enabled || ticket.epoch != epoch || ticket.messageId in seen ||
            ticket.order <= latestAccepted || !fresh(ticket.elapsed, now) || code.isBlank()) return false
        seen += ticket.messageId
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
        latestAccepted = ticket.order
        pending = Pending(ticket.messageId, ticket.order, ticket.elapsed, ticket.wallTime, code)
        return true
    }

    @Synchronized fun peek(now: Long): Pending? {
        if (!enabled || pending?.let { !fresh(it.elapsed, now) } == true) pending = null
        return pending
    }

    /** Atomic consumption also rejects a stale main-thread task after another SMS arrived. */
    @Synchronized fun take(expected: Pending, now: Long, clipboardTime: Long?): String? {
        if (peek(now) != expected) return null
        pending = null
        if (clipboardTime != null && clipboardTime > expected.wallTime) return null
        return expected.code
    }

    @Synchronized fun clear() { epoch++; pending = null }

    /** Only JDK values cross module class loaders. This state stays in the living host process. */
    @Synchronized fun save(): List<Any?> = listOf(sequence, latestAccepted, seen.toList(), pending?.let {
        listOf(it.messageId, it.order, it.elapsed, it.wallTime, it.code)
    })

    @Synchronized fun restore(state: Any?, now: Long) {
        val values = state as? List<*> ?: return
        val savedSequence = values.getOrNull(0) as? Long ?: return
        val savedLatest = values.getOrNull(1) as? Long ?: return
        if (savedSequence < 0 || savedLatest !in 0..savedSequence) return
        sequence = maxOf(sequence, savedSequence)
        latestAccepted = maxOf(latestAccepted, savedLatest)
        (values.getOrNull(2) as? List<*>)?.filterIsInstance<Long>()?.filter { it > 0 }
            ?.takeLast(MAX_SEEN)?.forEach(seen::add)
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
        val candidate = values.getOrNull(3) as? List<*> ?: return
        val id = candidate.getOrNull(0) as? Long ?: return
        val order = candidate.getOrNull(1) as? Long ?: return
        val elapsed = candidate.getOrNull(2) as? Long ?: return
        val wallTime = candidate.getOrNull(3) as? Long ?: return
        val code = candidate.getOrNull(4) as? String ?: return
        if (enabled && id in seen && order == latestAccepted && fresh(elapsed, now) && code.isNotBlank()) {
            pending = Pending(id, order, elapsed, wallTime, code)
        }
    }

    private fun fresh(elapsed: Long, now: Long) = now >= elapsed && now - elapsed < MAX_AGE_MS
}
