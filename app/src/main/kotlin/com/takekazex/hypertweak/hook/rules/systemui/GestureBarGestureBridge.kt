package com.takekazex.hypertweak.hook.rules.systemui

internal const val GESTURE_BAR_GESTURE_ACTION =
    "com.takekazex.hypertweak.action.GESTURE_BAR_GESTURE"
internal const val GESTURE_BAR_GESTURE_EXTRA =
    "com.takekazex.hypertweak.extra.GESTURE"
internal const val GESTURE_BAR_GESTURE_DISPLAY_EXTRA =
    "com.takekazex.hypertweak.extra.DISPLAY_ID"
internal const val GESTURE_BAR_GESTURE_TOKEN_EXTRA =
    "com.takekazex.hypertweak.extra.GESTURE_TOKEN"

internal enum class GestureBarGesture(val persistedId: Int) {
    LONG_PRESS(1),
    DOUBLE_TAP(2);

    companion object {
        fun fromPersistedId(id: Int): GestureBarGesture? =
            entries.firstOrNull { it.persistedId == id }
    }
}

internal object GestureBarLongPressTiming {
    private const val OWNERSHIP_LEAD_MS = 75L
    private const val MIN_TIMEOUT_MS = 200L

    fun recognitionTimeout(systemTimeoutMs: Long): Long {
        if (systemTimeoutMs <= 0L) return MIN_TIMEOUT_MS
        return (systemTimeoutMs - OWNERSHIP_LEAD_MS)
            .coerceAtLeast(MIN_TIMEOUT_MS)
            .coerceAtMost(systemTimeoutMs)
    }
}

internal object GestureBarPilferTiming {
    const val ASSOCIATION_WINDOW_MS = 125L
    const val PRE_CANDIDATE_HOLD_MS = 32L
    const val SYSTEM_UI_CLAIM_LATCH_MS = 64L
    const val FAIL_OPEN_GRACE_MS = 250L

    fun failOpenDeadline(downEventTime: Long, longPressTimeoutMs: Long): Long =
        downEventTime + longPressTimeoutMs + FAIL_OPEN_GRACE_MS
}

internal object GestureBarMonitorNames {
    private const val LAUNCHER_SWIPE_UP = "[Gesture Monitor] swipe-up"
    private const val HYPERTWEAK_PREFIX = "[Gesture Monitor] HyperTweakGestureBar-"

    fun isLauncherSwipeUp(name: String): Boolean = name == LAUNCHER_SWIPE_UP

    fun isHyperTweakGestureBar(name: String): Boolean = name.startsWith(HYPERTWEAK_PREFIX)
}

/** Tracks ownership requests while a bottom-handle gesture is still undecided. */
internal class GestureBarPilferGate<T>(
    private val associationWindowMs: Long = GestureBarPilferTiming.ASSOCIATION_WINDOW_MS
) {
    private var candidateStartedAt = NO_TIME
    private val deferredRequests = mutableListOf<T>()

    fun beginCandidate(eventTime: Long): List<T> {
        val staleRequests = releaseCandidate()
        candidateStartedAt = eventTime
        return staleRequests
    }

    fun tryDefer(request: T, requestTime: Long): Boolean {
        if (candidateStartedAt == NO_TIME) return false
        val elapsed = requestTime - candidateStartedAt
        if (deferredRequests.isEmpty() && elapsed !in 0..associationWindowMs) {
            return false
        }
        if (request !in deferredRequests) deferredRequests += request
        return true
    }

    fun releaseCandidate(): List<T> {
        candidateStartedAt = NO_TIME
        if (deferredRequests.isEmpty()) return emptyList()
        return deferredRequests.toList().also { deferredRequests.clear() }
    }

    fun consumeCandidate() {
        candidateStartedAt = NO_TIME
        deferredRequests.clear()
    }

    fun candidateStartedAtOrNull(): Long? =
        candidateStartedAt.takeUnless { it == NO_TIME }

    fun hasDeferredRequests(): Boolean = deferredRequests.isNotEmpty()

    private companion object {
        const val NO_TIME = Long.MIN_VALUE
    }
}
