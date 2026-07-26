package com.takekazex.hypertweak.hook.rules.systemui

internal enum class GestureBarGesture {
    LONG_PRESS,
    DOUBLE_TAP
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
