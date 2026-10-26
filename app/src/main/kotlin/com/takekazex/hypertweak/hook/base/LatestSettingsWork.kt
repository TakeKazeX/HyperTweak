package com.takekazex.hypertweak.hook.base

/** Coalesced serial side effects. Retirement waits for a started commit and cancels queued work. */
internal class LatestSettingsWork<T : Any>(
    private val execute: (() -> Unit) -> Unit,
    private val apply: (T) -> Unit,
    private val failure: (Throwable) -> Unit,
) {
    private val state = Any()
    private val commit = Any()
    private var desired: T? = null
    private var applied: T? = null
    private var queued = false
    private var running = false
    private var retired = false

    fun request(value: T): Boolean = synchronized(state) {
        if (retired) return false
        desired = value
        if (applied == value && !running) return true
        enqueue()
        false
    }

    private fun enqueue() {
        if (queued || running || retired) return
        queued = true
        try { execute(::drain) } catch (error: Throwable) { queued = false; failure(error) }
    }

    private fun drain(): Unit = synchronized(commit) {
        val value = synchronized(state) {
            queued = false
            if (retired || desired == applied) return
            running = true
            checkNotNull(desired)
        }
        var success = false
        try { apply(value); success = true }
        catch (error: Throwable) { failure(error) }
        finally {
            synchronized(state) {
                running = false
                if (success) applied = value
                // A failed unchanged value is retried by the shared dispatcher's bounded budget.
                if (desired != value) enqueue()
            }
        }
    }

    fun retire() {
        synchronized(state) { retired = true; desired = null }
        synchronized(commit) { /* A started transaction must finish before replacement. */ }
    }
}
