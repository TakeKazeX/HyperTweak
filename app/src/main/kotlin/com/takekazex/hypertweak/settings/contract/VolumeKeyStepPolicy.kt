package com.takekazex.hypertweak.settings.contract

internal data class VolumeKeyStepConfiguration(val steps: Int, val scope: Int, val enabled: Boolean)

/** Stateless integer percentage increments shared by AudioService and the control-center key controller. */
internal object VolumeKeyStepPolicy {
    const val MIN_STEPS = 5
    const val MAX_STEPS = 100

    fun configuration(steps: Int, scope: Int, enabled: Boolean = true) = VolumeKeyStepConfiguration(
        steps.coerceIn(MIN_STEPS, MAX_STEPS),
        scope.coerceIn(PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY,
            PlatformSettingsKeys.VOLUME_KEY_SCOPE_ACTIVE_STREAM),
        enabled
    )

    fun appliesToStream(scope: Int, stream: Int): Boolean =
        scope == PlatformSettingsKeys.VOLUME_KEY_SCOPE_ACTIVE_STREAM || stream == 3

    /** Non-divisors of 100 use the nearest whole percent; endpoints clamp normally. */
    fun percentStep(steps: Int): Int {
        val count = steps.coerceIn(MIN_STEPS, MAX_STEPS)
        return (100 + count / 2) / count
    }

    /**
     * Add/subtract from the current rounded percentage, including a value selected by dragging.
     * Project once to a representable public index; never snap to a zero-based stop grid.
     * Coarse streams advance at least one index because they cannot represent smaller changes.
     */
    fun nextIndex(current: Int, minimum: Int, maximum: Int, steps: Int, direction: Int): Int {
        if (maximum <= minimum || direction == 0) return current
        val bounded = current.coerceIn(minimum, maximum)
        val range = maximum.toLong() - minimum
        val percent = ((bounded.toLong() - minimum) * 100 + range / 2) / range
        val targetPercent = (percent + if (direction > 0) percentStep(steps) else -percentStep(steps))
            .coerceIn(0L, 100L)
        val projected = (minimum + (range * targetPercent + 50) / 100).toInt()
        return if (direction > 0) {
            projected.coerceAtLeast((bounded.toLong() + 1).coerceAtMost(maximum.toLong()).toInt())
        } else {
            projected.coerceAtMost((bounded.toLong() - 1).coerceAtLeast(minimum.toLong()).toInt())
        }
    }
}
