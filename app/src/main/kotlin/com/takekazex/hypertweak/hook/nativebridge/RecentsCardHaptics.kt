package com.takekazex.hypertweak.hook.nativebridge

import android.content.Context
import android.os.SystemClock
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method

/** Immediate, fresh Xiaomi selection feedback, retired with the SystemUI publisher. */
internal class RecentsCardHaptics(
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val driver: Driver = MiuiDriver(),
) {
    internal interface Driver {
        fun prepare(context: Context): Boolean
        fun play()
        fun close()
    }

    companion object {
        const val ACTION = "com.takekazex.hypertweak.action.RECENTS_CARD_HAPTIC"
        const val EVENT_TIME = "hypertweak_haptic_event_time_nanos"
        private const val MESH_NORMAL = 0x10000005
    }
    private val timing = RecentsHapticTiming()
    private var closed = false

    init { reset() }

    fun prepare(context: Context): Boolean = !closed && driver.prepare(context)

    fun play(context: Context, eventTimeNanos: Long) {
        if (!prepare(context) || !timing.admit(eventTimeNanos, clock())) return
        driver.play()
    }

    fun reset() = timing.reset(clock())

    fun close() {
        if (closed) return
        closed = true
        reset()
        driver.close()
    }

    private class MiuiDriver : Driver {
        private var util: Any? = null
        private var perform: Method? = null
        private var release: Method? = null
        private var failed = false
        override fun prepare(context: Context): Boolean {
            if (failed) return false
            return runCatching {
                if (util == null) {
                    val type = Class.forName("miui.util.HapticFeedbackUtil")
                    perform = type.getMethod("performHapticFeedback", Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType)
                    release = type.getMethod("release")
                    util = type.getConstructor(Context::class.java, Boolean::class.javaPrimitiveType)
                        .newInstance(context, false)
                }
                true
            }.onFailure {
                failed = true
                DebugLog.w("RecentsCardHaptic", "MIUI selection haptic unavailable", it)
            }.getOrDefault(false)
        }

        override fun play() {
            runCatching {
                // false preserves MIUI's own haptic-disable policy and motor adaptation.
                perform?.invoke(util, MESH_NORMAL, false)
            }.onFailure { DebugLog.w("RecentsCardHaptic", "MIUI selection haptic failed", it) }
        }

        override fun close() {
            val instance = util
            util = null
            if (instance != null) runCatching { release?.invoke(instance) }
                .onFailure { DebugLog.w("RecentsCardHaptic", "MIUI haptic release failed", it) }
            perform = null
            release = null
        }
    }
}
