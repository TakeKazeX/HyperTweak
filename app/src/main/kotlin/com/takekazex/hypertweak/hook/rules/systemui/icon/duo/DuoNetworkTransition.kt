package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

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

    fun submit(next: DuoRepresentation, animate: Boolean): Boolean {
        if (next == target) return false
        start = weights
        target = next
        if (!animate || start.isEmpty()) { finish(); return false }
        return true
    }

    fun advance(fraction: Float) {
        val next = target ?: return
        val p = fraction.coerceIn(0f, 1f)
        weights = buildMap {
            start.forEach { (key, weight) -> if (weight * (1f - p) > 0f) put(key, weight * (1f - p)) }
            put(next, (get(next) ?: 0f) + p)
        }
    }

    fun finish() {
        weights = target?.let { mapOf(it to 1f) } ?: emptyMap()
        start = weights
    }
}
