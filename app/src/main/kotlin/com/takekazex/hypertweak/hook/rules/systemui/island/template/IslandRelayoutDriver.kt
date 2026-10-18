package com.takekazex.hypertweak.hook.rules.systemui.island.template

import android.view.View
import android.view.ViewTreeObserver
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandRelayoutState

/** One owner and one frame listener for settings, rotation, and asynchronous template binds. */
internal class IslandRelayoutDriver(
    val window: View,
    private val ready: () -> Boolean,
    private val prepare: () -> Unit,
    private val publish: () -> Unit,
    private val failure: (Throwable) -> Unit,
) {
    private val state = IslandRelayoutState()
    private var observer: ViewTreeObserver? = null
    private var retired = false
    private val draw = ViewTreeObserver.OnPreDrawListener { onPreDraw() }
    fun onPreDraw(): Boolean =
        if (retired || state.phase == IslandRelayoutState.Phase.IDLE) true else {
            runCatching {
                if (!ready() || window.isLayoutRequested) true else {
                    val token = state.generation
                    when (state.phase) {
                        IslandRelayoutState.Phase.PREPARE -> {
                            prepare()
                            state.prepared(token)
                            // Never publish animation/clipping coordinates from the old layout pass.
                            IslandLayoutInvalidation.request(window)
                            false
                        }
                        IslandRelayoutState.Phase.PUBLISH -> {
                            publish()
                            state.published(token)
                            // Native setTo/factory callbacks may request a final template layout.
                            // Cancel this draw; the next traversal uses those committed widths.
                            !window.isLayoutRequested
                        }
                        IslandRelayoutState.Phase.IDLE -> true
                    }
                }
            }.getOrElse { error ->
                fail(error)
                true
            }
        }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { runCatching { request() }.onFailure(::fail) }
        override fun onViewDetachedFromWindow(v: View) { runCatching { unobserve() }.onFailure(::fail) }
    }
    private fun fail(error: Throwable) {
        state.cancel()
        runCatching { failure(error) }
    }
    init { window.addOnAttachStateChangeListener(attach) }
    private fun observe() {
        if (retired || !window.isAttachedToWindow) return
        val current = window.viewTreeObserver
        if (observer === current) return
        unobserve()
        current.addOnPreDrawListener(draw)
        observer = current
    }
    private fun unobserve() { observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(draw); observer = null }
    fun request() {
        if (retired) return
        state.request()
        observe()
        IslandLayoutInvalidation.request(window)
        window.invalidate()
    }
    fun retire() {
        retired = true
        state.cancel()
        unobserve()
        window.removeOnAttachStateChangeListener(attach)
    }
}
