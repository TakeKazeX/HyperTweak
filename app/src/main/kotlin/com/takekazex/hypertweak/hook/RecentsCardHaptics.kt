package com.takekazex.hypertweak.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method

/**
 * Xiaomi's predefined selection effect, owned by the SystemUI publisher and
 * retired with it on API-102 replacement.
 *
 * The target maps MESH_NORMAL to HEAVY_CLICK and measures 48 ms in the vendor
 * HAL. Requests are coalesced to the next free slot so the effect is never
 * superseded by a faster card swipe; no custom waveform is synthesised.
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
    private var pendingContext: Context? = null
    private val pendingPlay = Runnable {
        val context = pendingContext ?: return@Runnable
        pendingContext = null
        playNow()
    }

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
        val wait = RecentsHapticTiming.delayUntilNext(lastPlayedAt, SystemClock.uptimeMillis())
        if (wait <= 0L) {
            handler.removeCallbacks(pendingPlay)
            pendingContext = null
            playNow()
        } else {
            // Coalesce a burst into the next free slot; the trailing event is
            // preserved so a fast swipe never loses its final tick.
            pendingContext = context.applicationContext ?: context
            if (!handler.hasCallbacks(pendingPlay)) handler.postDelayed(pendingPlay, wait)
        }
    }

    private fun playNow() {
        lastPlayedAt = SystemClock.uptimeMillis()
        runCatching {
            // false preserves MIUI's own haptic-disable policy and motor adaptation.
            perform?.invoke(util, MESH_NORMAL, false)
        }.onFailure { DebugLog.w("RecentsCardHaptic", "MIUI selection haptic failed", it) }
    }

    fun cancelPending() {
        handler.removeCallbacks(pendingPlay)
        pendingContext = null
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
