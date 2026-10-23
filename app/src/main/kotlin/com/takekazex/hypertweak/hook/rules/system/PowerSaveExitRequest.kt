package com.takekazex.hypertweak.hook.rules.system

/** Retry a failed connection command, then relinquish ownership as soon as it is dispatched. */
internal class PowerSaveExitRequest(
    private val dispatch: (Int) -> Unit,
    private val onFailure: (Int, Throwable, Boolean) -> Unit,
) {
    private var user: Int? = null
    private var attempts = 0
    private var dueAt = 0L
    val pending: Boolean get() = user != null

    fun cancel() {
        user = null
        attempts = 0
        dueAt = 0L
    }

    fun submit(userId: Int) {
        cancel()
        user = userId
    }

    /** No state-based replay after success: charging users may turn power saving back on. */
    fun step(nowMs: Long): Long? {
        val target = user ?: return null
        if (nowMs < dueAt) return dueAt - nowMs
        attempts++
        return try {
            dispatch(target)
            cancel()
            null
        } catch (error: Exception) {
            val exhausted = attempts >= 3
            onFailure(target, error, exhausted)
            if (exhausted) {
                cancel()
                null
            } else {
                val delay = if (attempts == 1) 250L else 1_000L
                dueAt = nowMs + delay
                delay
            }
        }
    }
}
