package com.takekazex.hypertweak.hook.nativebridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method

/**
 * Xiaomi's predefined selection effect, owned by the SystemUI publisher and
 * retired with it on API-102 replacement.
 *
 * The launcher emits one signal per card crossing, but they do not arrive one
 * per frame: the cross-process hop and the recents entry animation both batch
 * them, so a fast flick is delivered as a burst. Playing a burst straight away
 * is wrong in both directions - the framework keeps only one running and one
 * pending vibration and supersedes the pending one, so the tail is silently
 * cancelled, while coalescing the burst into a single tick (the previous policy)
 * discards the crossings themselves.
 *
 * This keeps an ordered queue instead: every crossing is played, paced at the
 * motor's own drain rate, so a fast swipe hears each card slightly late rather
 * than losing the later ones. The effect stays stock Xiaomi
 * (MESH_NORMAL -> HEAVY_CLICK); no waveform is synthesised, so MIUI's own
 * haptic-disable policy and motor adaptation still apply.
 */
internal class RecentsCardHaptics {
    companion object {
        const val ACTION = "com.takekazex.hypertweak.action.RECENTS_CARD_HAPTIC"
        private const val MESH_NORMAL = 0x10000005
    }
    private val handler = Handler(Looper.getMainLooper())
    private var util: Any? = null
    private var perform: Method? = null
    private var release: Method? = null
    private var failed = false
    private var lastPlayedAt = 0L
    /** Crossings observed but not yet played, oldest first. */
    private val pending = ArrayDeque<Unit>()
    private val drain = Runnable { drainNow() }

    fun prepare(context: Context): Boolean {
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

    fun play(context: Context) {
        if (!prepare(context)) return
        // A backlog longer than the motor can ever catch up would only add lag;
        // drop the oldest crossings so the most recent cards still answer.
        repeat(RecentsHapticTiming.overflow(pending.size)) { pending.removeFirst() }
        pending.addLast(Unit)
        if (!handler.hasCallbacks(drain)) schedule()
    }

    /** Next free motor slot, preserving order; never sends into a busy session. */
    private fun schedule() {
        val wait = RecentsHapticTiming.delayUntilNext(lastPlayedAt, SystemClock.uptimeMillis())
        if (wait <= 0L) drainNow() else handler.postDelayed(drain, wait)
    }

    private fun drainNow() {
        if (pending.isEmpty()) return
        pending.removeFirst()
        playNow()
        if (pending.isNotEmpty()) schedule()
    }

    private fun playNow() {
        lastPlayedAt = SystemClock.uptimeMillis()
        runCatching {
            // false preserves MIUI's own haptic-disable policy and motor adaptation.
            perform?.invoke(util, MESH_NORMAL, false)
        }.onFailure { DebugLog.w("RecentsCardHaptic", "MIUI selection haptic failed", it) }
    }

    fun cancelPending() {
        handler.removeCallbacks(drain)
        pending.clear()
    }

    fun close() {
        cancelPending()
        val instance = util
        util = null
        if (instance != null) runCatching { release?.invoke(instance) }
            .onFailure { DebugLog.w("RecentsCardHaptic", "MIUI haptic release failed", it) }
        perform = null
        release = null
    }
}
