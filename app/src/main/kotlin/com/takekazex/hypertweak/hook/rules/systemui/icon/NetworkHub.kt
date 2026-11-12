package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.presentation.icon.NetworkState
import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * The current Wi-Fi and default-route observations of the SystemUI process.
 *
 * [WifiPipelineAdapter] is the only producer. The state outlives every consumer generation, and a
 * subscription hears the current value right after it is made, so a renderer reinstalled on its
 * own never starts from an empty network.
 */
internal object NetworkHub {
    private val values = ReplayState(NetworkState(), IconMainThread::post) { callback ->
        runCatching(callback).onFailure { DebugLog.w("IconTuner", "network state listener failed", it) }
    }

    val state: NetworkState get() = values.value

    /** True while the shared Wi-Fi flows are collected; false means the Wi-Fi fields are stale. */
    @Volatile var wifiBound = false
        internal set

    /** Applies [update] to the state current on the main thread when it runs. */
    fun publish(update: (NetworkState) -> NetworkState) {
        IconMainThread.run { values.publish(update(values.value)) }
    }

    fun subscribe(owner: BaseHooker, callback: () -> Unit) {
        owner.ownSubscription(values.subscribe(callback))
    }
}
