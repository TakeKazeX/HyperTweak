package com.takekazex.hypertweak.hook.rules.system

/** Serial, finite reconciliation of an asynchronous vendor command with observable state. */
internal class PowerSaveReconciler(
    private val backend: Backend,
    private val onFailure: (String, Throwable?) -> Unit,
) {
    data class Target(val userId: Int, val enabled: Boolean)
    data class State(val nativeEnabled: Boolean, val frameworkEnabled: Boolean) {
        fun matches(target: Target): Boolean = nativeEnabled == target.enabled &&
            (target.enabled || !frameworkEnabled)
    }
    interface Backend {
        fun read(userId: Int): State
        fun requestNative(target: Target)
        fun clearFramework(): Boolean
    }
    sealed interface Result {
        data object Idle : Result
        data class Waiting(val delayMs: Long) : Result
        data class Confirmed(val target: Target, val state: State, val changed: Boolean) : Result
        data class Failed(val target: Target, val state: State?) : Result
    }

    private var target: Target? = null
    private var attempts = 0
    private var dueAt = 0L
    private var forceNative = false
    private var dispatched = false
    private var confirmed = false
    private var exhausted = false
    val pending: Boolean get() = target != null && !confirmed && !exhausted

    fun cancel() {
        target = null
        attempts = 0
        dueAt = 0L
        forceNative = false
        dispatched = false
        confirmed = false
        exhausted = false
    }

    /** New physical transitions supersede queued work even when the reported flag already matches. */
    fun aim(next: Target, force: Boolean = false, retryExhausted: Boolean = false) {
        if (target == next && !force && !(exhausted && retryExhausted)) return
        val supersedesQueuedCommand = dispatched && target != next
        cancel()
        target = next
        forceNative = force || supersedesQueuedCommand
    }

    fun step(nowMs: Long): Result {
        val goal = target ?: return Result.Idle
        if (nowMs < dueAt) return Result.Waiting(dueAt - nowMs)
        val state = try {
            backend.read(goal.userId)
        } catch (t: Throwable) {
            onFailure("read power-save state", t)
            null
        }
        if (state?.matches(goal) == true && !forceNative) {
            val changed = dispatched && !confirmed
            confirmed = true
            exhausted = false
            attempts = 0
            return Result.Confirmed(goal, state, changed)
        }
        if (confirmed) {
            // A later state change starts a new reconciliation episode for the active target.
            confirmed = false
            dispatched = false
        }
        if (exhausted || attempts >= RETRY_DELAYS_MS.size) {
            exhausted = true
            return Result.Failed(goal, state)
        }
        val attempt = attempts++
        if (state != null) {
            if (forceNative || state.nativeEnabled != goal.enabled || (!goal.enabled && state.frameworkEnabled)) {
                try {
                    backend.requestNative(goal)
                    forceNative = false
                    dispatched = true
                } catch (t: Throwable) {
                    onFailure("request native power save enabled=${goal.enabled}", t)
                }
            }
            // A provider failure must not prevent clearing the independent framework request.
            if (!goal.enabled && (state.frameworkEnabled || forceNative || dispatched)) {
                try {
                    backend.clearFramework()
                    dispatched = true
                } catch (t: Throwable) {
                    onFailure("clear framework power save", t)
                }
            }
        }
        val delay = RETRY_DELAYS_MS[attempt]
        dueAt = nowMs + delay
        return Result.Waiting(delay)
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(250L, 1_000L, 3_000L)
    }
}
