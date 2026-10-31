package com.takekazex.hypertweak.hook.rules.systemui.notification

/** One temporary override shared by both clock surfaces; persistent settings stay independent. */
internal class ClockSecondsPolicy(private val timeoutMillis: Long) {
    private var override: Boolean? = null
    private var expiresAt = 0L

    fun isActive(now: Long): Boolean = override != null && now < expiresAt

    fun enabled(baseline: Boolean, now: Long): Boolean =
        if (isActive(now)) override ?: baseline else baseline

    fun toggle(baseline: Boolean, now: Long) {
        if (isActive(now)) {
            clear()
        } else {
            override = !baseline
            expiresAt = now + timeoutMillis
        }
    }

    fun clear() {
        override = null
        expiresAt = 0L
    }
}
