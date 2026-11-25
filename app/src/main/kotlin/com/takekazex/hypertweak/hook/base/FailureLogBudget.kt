package com.takekazex.hypertweak.hook.base

/**
 * Log budget for failures that can repeat on hot host paths: the first [FULL] occurrences of
 * one site are logged with their stack, then only each power-of-ten count, so a callback that
 * fails on every frame stays visible without flooding the log.
 */
internal object FailureLogBudget {
    const val FULL = 3L

    enum class Entry { FULL, SUMMARY, NONE }

    fun entry(count: Long): Entry = when {
        count <= FULL -> Entry.FULL
        isPowerOfTen(count) -> Entry.SUMMARY
        else -> Entry.NONE
    }

    private fun isPowerOfTen(value: Long): Boolean {
        var v = value
        while (v >= 10 && v % 10 == 0L) v /= 10
        return v == 1L
    }
}
