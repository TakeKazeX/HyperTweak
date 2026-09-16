package com.takekazex.hypertweak.hook.rules.systemui.icon

/**
 * The one numeric contract for the "limit notification icons" control:
 * **minimum 0, default 3, maximum 15**.
 *
 * The settings row (slider steps, typed dialog) and [NotificationMaxNumberHooker] must agree on
 * these three numbers, otherwise the UI can offer a value the hook silently rewrites, or the hook
 * can clamp a value the UI still displays. Keeping them here — where the hook already lives — means
 * the settings page imports the contract instead of duplicating it, and the preference key
 * documents the same triple at its declaration in `Preferences`.
 *
 * [MIN] is a valid setting, not "unset": the host uses the number as `take(n)` and as
 * `NotificationIconContainer.setMaxIconsAmount(n)`, so 0 keeps the container empty.
 */
object NotificationIconLimit {
    /** Smallest accepted value (no notification icons are kept). */
    const val MIN = 0

    /** Largest accepted value. */
    const val MAX = 15

    /** Value used when the preference has never been written. */
    const val DEFAULT = 3

    /** The accepted span, derived from [MIN] and [MAX] so it can never drift from them. */
    val RANGE = MIN..MAX

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)

    /**
     * Miuix `Slider` step count for an integer range.
     *
     * Miuix resolves the dragged fraction as `round(fraction * (steps + 1))`, so a range of
     * `a..b` needs `b - a - 1` intermediate steps to snap onto exactly `b - a + 1` integer
     * positions. Passing the interval count instead would skip the endpoints' neighbours.
     */
    fun sliderSteps(range: IntRange = RANGE): Int =
        (range.last - range.first - 1).coerceAtLeast(0)
}
