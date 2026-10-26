package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import java.util.IdentityHashMap
import kotlin.math.abs

/** Native endpoint alphas borrowed only while a carrier overlay can draw them. Main-thread only. */
internal class CarrierEndpointAlphas<T : Any>(
    private val read: (T) -> Float, private val write: (T, Float) -> Unit
) {
    private class State(var saved: Float, var applied: Float)
    private val states = IdentityHashMap<T, State>()

    fun suppress(target: T, ready: Boolean) {
        val current = read(target)
        val state = states[target] ?: State(current, current).also { states[target] = it }
        if (abs(current - state.applied) > .001f) state.saved = current
        val alpha = if (ready) 0f else state.saved
        if (current != alpha) write(target, alpha)
        state.applied = alpha
    }

    fun restore(retained: Set<T> = emptySet()) {
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val (target, state) = iterator.next()
            if (target in retained) continue
            if (abs(read(target) - state.applied) < .001f) write(target, state.saved)
            iterator.remove()
        }
    }
}
