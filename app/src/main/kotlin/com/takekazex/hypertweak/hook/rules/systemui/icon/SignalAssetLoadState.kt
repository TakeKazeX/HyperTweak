package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.settings.contract.SignalSvgStyle

import java.util.concurrent.atomic.AtomicLong

/** Serializes asset retirement and completion; IO and rendering happen outside this monitor. */
internal class SignalAssetLoadState<T>(
    private val generation: AtomicLong,
    private val retryDelayMs: Long
) {
    data class Ticket(val generation: Long, val style: SignalSvgStyle)

    private var style = SignalSvgStyle.HYPEROS3
    private var inFlight: Ticket? = null
    private var retryAfterMs = 0L
    private var loaded: T? = null

    @Synchronized
    fun advance(nextStyle: SignalSvgStyle = SignalSvgStyle.HYPEROS3): Long {
        val next = generation.incrementAndGet()
        style = nextStyle
        inFlight = null
        retryAfterMs = 0L
        loaded = null
        return next
    }

    @Synchronized
    fun begin(expectedGeneration: Long, nowMs: Long): Ticket? {
        if (generation.get() != expectedGeneration || loaded != null || inFlight != null ||
            nowMs < retryAfterMs) return null
        return Ticket(expectedGeneration, style).also { inFlight = it }
    }

    @Synchronized
    fun complete(ticket: Ticket, value: T?, nowMs: Long): Boolean {
        if (generation.get() != ticket.generation || inFlight !== ticket) return false
        inFlight = null
        loaded = value
        retryAfterMs = if (value == null) nowMs + retryDelayMs else 0L
        return true
    }

    @Synchronized
    fun current(expectedGeneration: Long): T? =
        loaded.takeIf { generation.get() == expectedGeneration }
}
