package com.takekazex.hypertweak.dock

/** A failed renderer cannot turn every WMS traversal into a new app/texture allocation. */
class DockRecoveryPolicy {
    private var identity: String? = null
    private var failures = 0
    private var retryAt = 0L
    fun permit(key: String, now: Long): Boolean {
        if (identity != key) { identity = key; failures = 0; retryAt = 0 }
        return failures < 4 && now >= retryAt
    }
    fun failed(key: String, now: Long): Long? {
        if (identity != key) permit(key, now)
        failures++
        val delay = when (failures) { 1 -> 2_000L; 2 -> 8_000L; 3 -> 30_000L; else -> return null }
        retryAt = now + delay
        return delay
    }
    fun reset() { identity = null; failures = 0; retryAt = 0 }
}
