package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.presentation.icon.MobileSignalState
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one published mobile-signal state of the SystemUI process.
 *
 * [StackedSignalHooker] owns the host pipeline ([MobileSignalSource]) and publishes every reducer
 * pass here. Consumers (Duo, the control-center carrier rows) read [state] instead of keeping
 * their own copy, and a subscription is told the current state right after it is made. A
 * consumer that is reinstalled on its own, as a scoped settings reload does, therefore renders
 * from the live state at once instead of an empty one until the next host emission.
 */
internal object MobileSignalHub {
    private const val TAG = "IconTuner"

    private class Listener(val onChange: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** The settled state; see [MobileSignalState.settle]. Written on the main thread only. */
    @Volatile
    var state: MobileSignalState = MobileSignalState()
        private set

    /** Main thread. Publishes one reducer pass; listeners hear only real changes. */
    fun publish(raw: MobileSignalState, ready: Boolean) {
        val next = MobileSignalState.settle(state, raw, ready)
        if (next == state) return
        state = next
        listeners.forEach(::notify)
    }

    /** Listens for the owner's current generation; the subscription retires with it. */
    fun subscribe(owner: BaseHooker, onChange: () -> Unit) {
        val listener = Listener(onChange)
        listeners += listener
        owner.ownSubscription(AutoCloseable { listeners.remove(listener) })
        main.post { if (listener in listeners) notify(listener) }
    }

    private fun notify(listener: Listener) {
        runCatching(listener.onChange).onFailure { DebugLog.w(TAG, "mobile state listener failed", it) }
    }
}
