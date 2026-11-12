package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import com.takekazex.hypertweak.settings.contract.IconTunerContract
import com.takekazex.hypertweak.util.logging.DebugLog

/** Which sibling of the host's `keyguard_left_frame` is drawn; pure so the rules stay testable. */
internal object LeftCarrierSwapPolicy {
    data class Target(val carrierAlpha: Float, val iconsAlpha: Float)

    val NATIVE = Target(1f, 1f)
    private val ICONS = Target(0f, 1f)
    private val CARRIER = Target(1f, 0f)

    const val INTRO_MS = 1500L

    /**
     * The host shows exactly one of carrier, clock and alarm in that FrameLayout. The left icons
     * follow the clock/alarm anchors, so they only collide with the carrier.
     */
    fun resolve(carrierShown: Boolean, iconsShown: Boolean, introActive: Boolean, manualIcons: Boolean? = null): Target = when {
        !carrierShown -> NATIVE
        !iconsShown -> CARRIER
        manualIcons != null -> if (manualIcons) ICONS else CARRIER
        introActive -> CARRIER
        else -> ICONS
    }

    /** A tap shows the other view; it is a no-op while only one of them can be shown. */
    fun toggled(current: Target?, iconsShown: Boolean): Boolean? = when {
        current == null || current == NATIVE || !iconsShown -> null
        else -> current != ICONS
    }

    /**
     * The carrier-first intro across a dark period. Screen-off only ARMS it: the bar is still drawn
     * while the screen dims, so switching then would flash the carrier. Once the bar is hidden the
     * intro HOLDS the carrier, so a reveal before the late screen-on broadcast cannot flash the
     * icons; screen-on then starts the timed WINDOW.
     */
    enum class IntroPhase { NONE, ARMED, HELD, WINDOW }

    fun introActive(mode: Int, phase: IntroPhase, now: Long, windowEnd: Long): Boolean =
        mode == IconTunerContract.LEFT_KEYGUARD_CARRIER_INTRO &&
            (phase == IntroPhase.HELD || phase == IntroPhase.WINDOW && now < windowEnd)

    fun settle(phase: IntroPhase, barShown: Boolean): IntroPhase =
        if (phase == IntroPhase.ARMED && !barShown) IntroPhase.HELD else phase
}

/** Per-state crossfade bookkeeping. */
internal class LeftCarrierSwapState {
    var target: LeftCarrierSwapPolicy.Target? = null
    var animator: ValueAnimator? = null
    /** The frame's clickable flag before the tap toggle was installed; null when not installed. */
    var hostClickable: Boolean? = null
}

/**
 * Crossfades the lockscreen carrier label and the left container, which the host stacks at the
 * same start edge. It writes `transitionAlpha` only: the host drives the carrier's `alpha` with its
 * own Folme fades and owns its visibility, and the left handover owns the container's `alpha`.
 */
internal class LeftKeyguardCarrierSwap(private val host: Host) {
    interface Host {
        val retiring: Boolean
        val handler: Handler
        val mode: Int
        /** The lockscreen label keeps a glyph without left icons; see [LeftKeyguardAirplanePolicy]. */
        val labelKeepsAirplane: Boolean
        fun iconsShown(state: LeftState): Boolean
        fun keyguardStates(): List<LeftState>
        fun slotOf(view: View): String?
    }

    private val airplane = LeftKeyguardAirplane(object : LeftKeyguardAirplane.Host {
        override val retiring get() = host.retiring
        override fun slotOf(view: View) = host.slotOf(view)
        override fun requestUpdate() {
            host.handler.removeCallbacks(airplaneRefresh)
            host.handler.post(airplaneRefresh)
        }
    })
    private val airplaneRefresh = Runnable { if (!host.retiring) updateAll() }
    /** The airplane's own screen-on intro, in every carrier mode. */
    // LeftRenderer constructs this controller before publishing its Handler. Resolve it only
    // after host attachment, never while the renderer singleton is still initializing.
    private val airplaneIntro by lazy {
        KeyguardAirplaneIntro(host.handler) { if (!host.retiring) updateAll() }
    }

    private var introPhase = LeftCarrierSwapPolicy.IntroPhase.NONE
    private var introUntil = 0L
    /** A tap's choice for this screen-on: true icons, false carrier, null the mode's default. */
    private var manualIcons: Boolean? = null
    private val introExpired = Runnable { if (!host.retiring) updateAll() }
    private var receiverContext: Context? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> host.handler.post { if (!host.retiring) onScreenOn() }
                Intent.ACTION_SCREEN_OFF -> host.handler.post { if (!host.retiring) onScreenOff() }
            }
        }
    }

    /** See [LeftCarrierSwapPolicy.IntroPhase]; the hidden bar later moves ARMED to HELD. */
    private fun onScreenOff() {
        manualIcons = null
        host.handler.removeCallbacks(introExpired)
        airplaneIntro.screenOff()
        if (host.mode != IconTunerContract.LEFT_KEYGUARD_CARRIER_INTRO) {
            updateAll()
            return
        }
        introPhase = LeftCarrierSwapPolicy.IntroPhase.ARMED
        updateAll()
    }

    private fun onScreenOn() {
        airplaneIntro.screenOn()
        if (host.mode != IconTunerContract.LEFT_KEYGUARD_CARRIER_INTRO) {
            updateAll()
            return
        }
        introPhase = LeftCarrierSwapPolicy.IntroPhase.WINDOW
        introUntil = SystemClock.uptimeMillis() + LeftCarrierSwapPolicy.INTRO_MS
        host.handler.removeCallbacks(introExpired)
        host.handler.postAtTime(introExpired, introUntil)
        updateAll()
    }

    fun updateAll() = host.keyguardStates().forEach { state ->
        if (state.carrierSwap != null) update(state) else updateAirplane(state)
    }

    /** Main thread only. */
    fun update(state: LeftState, animate: Boolean = true) {
        if (host.retiring || !state.isKeyguard) return
        val carrier = state.keyguardCarrier ?: return
        ensureReceiver(carrier.context)
        val swap = state.carrierSwap ?: LeftCarrierSwapState().also { state.carrierSwap = it }
        installToggle(state, swap)
        introPhase = LeftCarrierSwapPolicy.settle(introPhase, state.presentation.visible)
        val intro = LeftCarrierSwapPolicy.introActive(host.mode, introPhase, SystemClock.uptimeMillis(), introUntil)
        // A carrier whose labels are all hidden or blank shows nothing; do not wait on it.
        val carrierShown = carrier.visibility == View.VISIBLE && hasVisibleText(carrier)
        val iconsShown = state.leftContainer != null && host.iconsShown(state)
        val target = LeftCarrierSwapPolicy.resolve(carrierShown, iconsShown, intro, manualIcons)
        crossfade(state, swap, carrier, target, carrierShown, iconsShown, intro, animate)
        // The airplane moves on its own: with no left icons the carrier stays while the glyph leaves.
        updateAirplane(state, animate)
    }

    /** Runs even when no left container exists, including when Duo owns the airplane. */
    fun updateAirplane(state: LeftState, animate: Boolean = true) {
        if (host.retiring || !state.isKeyguard) return
        val carrier = state.keyguardCarrier ?: return
        if (!carrier.isAttachedToWindow) {
            releaseAirplane(state)
            return
        }
        ensureReceiver(carrier.context)
        val carrierShown = carrier.visibility == View.VISIBLE && hasVisibleText(carrier)
        val iconsShown = state.leftContainer != null && host.iconsShown(state)
        val target = state.carrierSwap?.target ?: LeftCarrierSwapPolicy.resolve(carrierShown, iconsShown, false)
        val intro = airplaneIntro.active(carrier.isShown)
        val snap = airplaneIntro.takeSnap() || !animate
        runCatching {
            airplane.update(state, state.keyguardAirplane, target, carrierShown, iconsShown, intro, snap, host.labelKeepsAirplane)
        }.onFailure {
            releaseAirplane(state)
            DebugLog.w(TAG, "keyguard airplane update failed", it)
        }
    }

    fun releaseAirplane(state: LeftState) = airplane.release(state, state.keyguardAirplane)

    private fun crossfade(state: LeftState, swap: LeftCarrierSwapState, carrier: View,
                          target: LeftCarrierSwapPolicy.Target, carrierShown: Boolean, iconsShown: Boolean,
                          intro: Boolean, animate: Boolean) {
        if (swap.target == target) {
            // A recreated container starts opaque; keep the settled target on it.
            if (swap.animator == null) apply(state, target.carrierAlpha, target.iconsAlpha)
            return
        }
        val previous = swap.target
        swap.target = target
        swap.animator?.cancel()
        swap.animator = null
        DebugLog.i(TAG, "keyguard carrier swap target=$target carrierShown=$carrierShown iconsShown=$iconsShown " +
            "presentation=${state.presentation} clones=${state.clones.keys.sorted()} intro=$intro manual=$manualIcons")
        val fromCarrier = carrier.transitionAlpha
        val fromIcons = state.leftContainer?.transitionAlpha ?: 1f
        if (!animate || previous == null || !ValueAnimator.areAnimatorsEnabled() || !carrier.isShown) {
            apply(state, target.carrierAlpha, target.iconsAlpha)
            return
        }
        swap.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_MS
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                apply(state, fromCarrier + (target.carrierAlpha - fromCarrier) * f,
                    fromIcons + (target.iconsAlpha - fromIcons) * f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (swap.animator === animation) swap.animator = null
                }
            })
            start()
        }
    }

    private fun hasVisibleText(view: View): Boolean = when {
        view.visibility != View.VISIBLE -> false
        view is TextView -> !view.text.isNullOrBlank()
        view is ViewGroup -> (0 until view.childCount).any { hasVisibleText(view.getChildAt(it)) }
        else -> false
    }

    /** Restores the native drawing of both views; main thread only. */
    fun release(state: LeftState) {
        val swap = state.carrierSwap ?: return
        state.carrierSwap = null
        swap.animator?.cancel()
        swap.animator = null
        swap.hostClickable?.let { clickable ->
            state.leftHost.setOnClickListener(null)
            state.leftHost.isClickable = clickable
        }
        apply(state, 1f, 1f)
    }

    /**
     * Tapping the shared frame shows the other view until the screen turns off. Installed only on a
     * frame the host left without a click listener, and removed with the swap.
     */
    private fun installToggle(state: LeftState, swap: LeftCarrierSwapState) {
        if (swap.hostClickable != null) return
        val frame = state.leftHost
        if (frame.hasOnClickListeners()) return
        swap.hostClickable = frame.isClickable
        frame.setOnClickListener {
            if (host.retiring || state.carrierSwap !== swap) return@setOnClickListener
            val next = LeftCarrierSwapPolicy.toggled(swap.target, host.iconsShown(state)) ?: return@setOnClickListener
            manualIcons = next
            update(state)
        }
    }

    /** Generation cleanup; the states themselves are released by their teardown. */
    fun reset() {
        host.handler.removeCallbacks(introExpired)
        receiverContext?.let { context -> runCatching { context.unregisterReceiver(receiver) } }
        receiverContext = null
        introUntil = 0L
        introPhase = LeftCarrierSwapPolicy.IntroPhase.NONE
        manualIcons = null
        airplaneIntro.reset()
        host.handler.removeCallbacks(airplaneRefresh)
    }

    private fun apply(state: LeftState, carrierAlpha: Float, iconsAlpha: Float) {
        state.keyguardCarrier?.let { if (it.transitionAlpha != carrierAlpha) it.transitionAlpha = carrierAlpha }
        state.leftContainer?.let { if (it.transitionAlpha != iconsAlpha) it.transitionAlpha = iconsAlpha }
    }

    private fun ensureReceiver(context: Context) {
        if (receiverContext != null) return
        val app = context.applicationContext ?: context
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            receiverContext = app
        }.onFailure { DebugLog.w(TAG, "keyguard carrier screen-on receiver failed", it) }
    }

    internal companion object {
        private const val TAG = "IconTuner"
        const val FADE_MS = 300L
    }
}
