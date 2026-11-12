package com.takekazex.hypertweak.presentation.icon

import kotlin.math.abs
import kotlin.math.exp

/** Unit-distance, critically damped spring. Retargeting retains both position and velocity. */
internal class AirplaneFlightSpring {
    var position = 0f
        private set
    var velocity = 0f
        private set
    private var origin = 0f
    private var initialVelocity = 0f
    private var target = 0f

    fun snap(value: Float) {
        target = value.coerceIn(0f, 1f)
        position = target
        origin = target
        velocity = 0f
        initialVelocity = 0f
    }

    /** The next clock starts at zero; no state changes before its first frame. */
    fun retarget(value: Float) {
        origin = position
        initialVelocity = velocity
        target = value.coerceIn(0f, 1f)
    }

    fun advance(elapsedMillis: Long): Boolean {
        val time = elapsedMillis.coerceAtLeast(0L) / 1000f
        val offset = origin - target
        val coefficient = initialVelocity + RATE * offset
        val decay = exp(-RATE * time)
        position = (target + (offset + coefficient * time) * decay).coerceIn(0f, 1f)
        velocity = (initialVelocity - RATE * coefficient * time) * decay
        val settled = abs(position - target) < .0005f && abs(velocity) < .005f
        if (settled || elapsedMillis >= MAX_DURATION_MS) snap(target)
        return settled || elapsedMillis >= MAX_DURATION_MS
    }

    companion object {
        const val MAX_DURATION_MS = 900L
        private const val RATE = 18f
    }
}
