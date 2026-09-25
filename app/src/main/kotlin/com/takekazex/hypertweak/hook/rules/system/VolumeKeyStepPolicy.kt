package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.Preferences

internal data class VolumeKeyStepConfiguration(
    val steps: Int,
    val scope: Int
)

internal data class VolumeKeyStepDelta(
    val delta: Int,
    val remainder: Int
)

/** Hardware-key step policy shared by system_server and the settings surface. */
internal object VolumeKeyStepPolicy {
    const val MIN_STEPS = 5
    const val MAX_STEPS = 100

    fun configuration(steps: Int, scope: Int): VolumeKeyStepConfiguration =
        VolumeKeyStepConfiguration(
            steps = steps.coerceIn(MIN_STEPS, MAX_STEPS),
            scope = scope.coerceIn(
                Preferences.VOLUME_KEY_SCOPE_MEDIA_ONLY,
                Preferences.VOLUME_KEY_SCOPE_ACTIVE_STREAM
            )
        )

    fun appliesToStream(scope: Int, stream: Int): Boolean = when (scope) {
        Preferences.VOLUME_KEY_SCOPE_ACTIVE_STREAM -> true
        else -> stream == STREAM_MUSIC
    }

    /** Distributes a logical stream range across exactly [steps] physical-key presses. */
    fun nextDelta(totalRange: Int, steps: Int, previousRemainder: Int): VolumeKeyStepDelta {
        val range = totalRange.coerceAtLeast(0)
        val stepCount = steps.coerceIn(MIN_STEPS, MAX_STEPS)
        val accumulated = previousRemainder.coerceIn(0, stepCount - 1) + range % stepCount
        return VolumeKeyStepDelta(
            delta = range / stepCount + accumulated / stepCount,
            remainder = accumulated % stepCount
        )
    }

    private const val STREAM_MUSIC = 3
}
