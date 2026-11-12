package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.presentation.icon.PanelState
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference

/**
 * Control-center panel motion: expansion, visibility, stretch and shade switching.
 *
 * [PanelMotionAdapter] is the only producer and the only hook on the host's panel callbacks.
 * [prepare] runs before the host applies a motion step, with the state that step will publish, so
 * consumers can restore their own state first; [subscribe] replays the current state.
 */
internal object PanelHub {
    @Volatile private var host = WeakReference<Any>(null)
    // Replays inline on the main thread, as the renderers' motion setup expects.
    private val updates = ReplayState(PanelState(), IconMainThread::run) { callback ->
        runCatching(callback).onFailure { DebugLog.w("IconPanel", "panel listener failed", it) }
    }
    private val beforeMotion = HostCallbackRegistry<PanelState> {
        DebugLog.w("IconPanel", "motion preparation failed", it)
    }

    val motionHost: Any? get() = host.get()
    val state: PanelState get() = updates.value

    fun observeHost(value: Any) {
        if (host.get() !== value) host = WeakReference(value)
    }

    /** [next] is the state the pending host step will publish; [state] is still the old one. */
    fun prepare(next: PanelState) = beforeMotion.dispatch(next)

    fun publish(next: PanelState) = IconMainThread.run { updates.publish(next) }

    /** Seeds the state from a living host after hot reload; null leaves that field unchanged. */
    fun recover(progress: Float?, visible: Boolean?, stretch: Float?) = IconMainThread.run {
        var next = updates.value
        if (progress != null) next = next.expansion(progress)
        if (visible != null) next = next.visibility(visible)
        if (stretch != null && stretch.isFinite()) next = next.copy(stretch = stretch)
        updates.publish(next)
    }

    fun subscribe(owner: BaseHooker, beforeMotion: (next: PanelState) -> Unit = {}, changed: () -> Unit) {
        owner.ownSubscription(this.beforeMotion.subscribe(0, owner.javaClass.simpleName, beforeMotion))
        owner.ownSubscription(updates.subscribe(changed))
    }
}
