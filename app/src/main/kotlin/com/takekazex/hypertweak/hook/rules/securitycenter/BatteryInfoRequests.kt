package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.util.BatteryInfoChannel
import java.util.concurrent.Executor

/** One worker and one conflated request; nothing schedules another read when the viewer goes away. */
internal class BatteryInfoRequests(
    private val clock: () -> Long,
    private val executor: Executor,
    private val collect: (isActive: () -> Boolean) -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private data class Request(val session: String, val issuedAt: Long)

    private val lock = Any()
    private var pending: Request? = null
    private var activeSession: String? = null
    private var stoppedSession: String? = null
    private var latestEvent = -1L
    private var lastStarted: Long? = null
    private var running = false
    private var closed = false

    fun request(session: String, issuedAt: Long) {
        synchronized(lock) {
            if (closed || !valid(session, issuedAt) || issuedAt < latestEvent || session == stoppedSession) return
            latestEvent = issuedAt
            activeSession = session
            pending = Request(session, issuedAt)
            if (running) return
            running = true
            try {
                executor.execute(::drain)
            } catch (failure: Exception) {
                running = false
                pending = null
                onFailure(failure)
            }
        }
    }

    fun stop(session: String, issuedAt: Long) {
        synchronized(lock) {
            if (closed || !valid(session, issuedAt) || issuedAt < latestEvent) return
            // An old page's delayed disposal must not cancel the next page's session.
            if (activeSession != null && activeSession != session) return
            latestEvent = issuedAt
            stoppedSession = session
            activeSession = null
            pending = null
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            activeSession = null
            pending = null
        }
    }

    private fun valid(session: String, issuedAt: Long): Boolean {
        val now = clock()
        return session.isNotBlank() && session.length <= 64 && issuedAt >= 0 &&
            issuedAt <= now && now - issuedAt < BatteryInfoChannel.REQUEST_LIFETIME_MS
    }

    private fun isActive(request: Request): Boolean = synchronized(lock) {
        !closed && activeSession == request.session && valid(request.session, request.issuedAt)
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null || closed) {
                    running = false
                    return
                }
                next
            }
            if (!isActive(request)) continue
            val now = clock()
            val previous = lastStarted
            if (previous != null && now - previous < MIN_START_INTERVAL_MS) continue
            lastStarted = now
            try {
                collect { isActive(request) }
            } catch (failure: Throwable) {
                onFailure(failure)
            }
        }
    }

    companion object {
        private const val MIN_START_INTERVAL_MS = 1_000L
    }
}
