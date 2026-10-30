package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.presentation.icon.duo.DuoContent
import kotlin.math.abs
import kotlin.math.exp

internal data class DuoRepresentation(val wifi: Boolean, val label: String?, val airplane: Boolean) {
    companion object {
        fun of(content: DuoContent) = DuoRepresentation(content.wifiLevel != null, content.networkLabel, content.airplaneMode)
    }
}

/** Retarget from the currently visible mixture, including a third representation mid-fade. */
internal class DuoNetworkTransition {
    private var start: Map<DuoRepresentation, Float> = emptyMap()
    var weights: Map<DuoRepresentation, Float> = emptyMap()
        private set
    private var target: DuoRepresentation? = null
    private var velocity: Map<DuoRepresentation, Float> = emptyMap()
    private var startVelocity: Map<DuoRepresentation, Float> = emptyMap()

    fun submit(next: DuoRepresentation, animate: Boolean): Boolean {
        if (next == target) return false
        start = weights
        startVelocity = velocity
        target = next
        if (!animate || start.isEmpty()) { finish(); return false }
        return true
    }

    fun advance(fraction: Float) {
        val next = target ?: return
        val p = fraction.coerceIn(0f, 1f)
        velocity = emptyMap()
        weights = buildMap {
            start.forEach { (key, weight) -> if (weight * (1f - p) > 0f) put(key, weight * (1f - p)) }
            put(next, (get(next) ?: 0f) + p)
        }
    }

    /** Analytic critically damped curve: frame-rate independent, with velocity retained on retarget. */
    fun advanceTime(elapsedMillis: Long): Boolean {
        val next = target ?: return true
        val seconds = elapsedMillis.coerceAtLeast(0L) / 1000f
        val decay = exp(-SPRING_RATE * seconds)
        val keys = start.keys + startVelocity.keys + next
        val speeds = LinkedHashMap<DuoRepresentation, Float>()
        var settled = true
        weights = buildMap {
            keys.forEach { key ->
                val destination = if (key == next) 1f else 0f
                val offset = (start[key] ?: 0f) - destination
                val initialSpeed = startVelocity[key] ?: 0f
                val coefficient = initialSpeed + SPRING_RATE * offset
                val value = destination + (offset + coefficient * seconds) * decay
                val speed = (initialSpeed - SPRING_RATE * coefficient * seconds) * decay
                if (value > 0f) put(key, value.coerceAtMost(1f))
                speeds[key] = speed
                if (abs(value - destination) > .0008f || abs(speed) > .01f) settled = false
            }
        }
        velocity = speeds
        if (settled || elapsedMillis >= MAX_DURATION_MS) { finish(); return true }
        return false
    }

    fun finish() {
        weights = target?.let { mapOf(it to 1f) } ?: emptyMap()
        start = weights
        velocity = emptyMap()
        startVelocity = emptyMap()
    }

    companion object {
        const val MAX_DURATION_MS = 900L
        private const val SPRING_RATE = 20f
    }
}


/** Match subscriptions before positions; two rows must never collapse into the same four dots. */
internal object DuoTransportSubscriptions {
    fun match(source: List<com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow>,
              target: List<com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow>,
              active: Int?): List<Int> {
        val used = HashSet<Int>()
        return source.mapIndexed { row, signal ->
            var index = target.indices.firstOrNull { it !in used && signal.subId >= 0 && target[it].subId == signal.subId }
            if (index == null) index = target.indices.firstOrNull {
                it !in used && (signal.subId < 0 || target[it].subId < 0) &&
                    (if (target.size == 1) signal.subId == active || active == null && row == 0
                     else target[it].slotIndex == signal.slotIndex)
            }
            if (index == null && source.size == 1 && target.size == 1 &&
                (signal.subId < 0 || target[0].subId < 0)) index = 0
            index?.also(used::add) ?: -1
        }
    }
}


/** Clear the destination before the moving signal reaches it, symmetrically when reversing. */
internal object DuoTransportPhases {
    private fun ease(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    fun inactiveOpacity(wifi: Float) = 1f - ease(wifi / .22f)
    fun labelOpacity(wifi: Float) = 1f - ease(wifi / .6f)
    fun wifiOpacity(wifi: Float) = ease((wifi - .12f) / .88f)
}
