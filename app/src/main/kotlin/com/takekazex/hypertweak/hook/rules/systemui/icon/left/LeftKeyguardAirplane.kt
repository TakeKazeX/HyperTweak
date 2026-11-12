package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Picture
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.view.animation.LinearInterpolator
import com.takekazex.hypertweak.presentation.icon.AirplaneFlightSpring
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.rules.systemui.icon.AirplaneGlyphTargets
import com.takekazex.hypertweak.hook.rules.systemui.icon.GlyphEndpoint
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelGlyphFrame
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.icon.SlotMaskApplier
import com.takekazex.hypertweak.presentation.icon.AirplaneLabelGeometry
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.util.logging.DebugLog

/** Where the lockscreen airplane is drawn while the carrier label carries the glyph; pure. */
internal object LeftKeyguardAirplanePolicy {
    /**
     * True: in the carrier label. False: with the icons (the left clone, or the right-side
     * airplane). Null: the label draws no glyph, so nothing is managed.
     *
     * While left icons can take over the carrier, the glyph follows the carrier/icons swap. Without
     * them, a Duo ring takes the airplane after the screen-on intro. A native right-side airplane
     * takes it after the intro only from a glyph replacing the label text. A glyph beside the text
     * stays in the label, and so does any glyph when [labelKeepsAirplane]: left placement is off
     * entirely, or the airplane itself is placed left, so the left-side label is where it belongs.
     */
    fun atLabel(
        glyph: Boolean,
        besideText: Boolean,
        carrierShown: Boolean,
        target: LeftCarrierSwapPolicy.Target?,
        iconsShown: Boolean,
        introActive: Boolean,
        duo: Boolean = false,
        labelKeepsAirplane: Boolean = false
    ): Boolean? = when {
        !glyph || target == null -> null
        !carrierShown || target.carrierAlpha < 1f -> false
        iconsShown || introActive -> true
        duo -> false
        else -> besideText || labelKeepsAirplane
    }
}

/** The views one lockscreen airplane moves between; the left container's state is one. */
internal interface KeyguardAirplaneScene {
    /** The host's lockscreen `MiuiCarrierTextLayout`. */
    val keyguardCarrier: View?
    /** The left container's airplane clone, which takes the airplane before Duo or the native slot. */
    val airplaneClone: View?
    /** The lockscreen right status icons holding the native airplane slot. */
    val airplaneContainer: ViewGroup?
}

/**
 * The screen-on intro of the lockscreen airplane glyph. Screen-off only ARMS it: the bar is still
 * drawn while the screen dims. Once the carrier is no longer shown the intro HOLDS the label, and
 * screen-on starts a [LeftCarrierSwapPolicy.INTRO_MS] window; a screen-on that never hid the
 * carrier starts at the label without a move. Main thread only.
 */
internal class KeyguardAirplaneIntro(private val handler: Handler, private val expired: () -> Unit) {
    private var armed = false
    private var held = false
    private var until = 0L
    private var snap = false
    private val expiry = Runnable { expired() }

    fun screenOff() {
        handler.removeCallbacks(expiry)
        armed = true
    }

    fun screenOn() {
        snap = !held
        armed = false
        held = false
        until = SystemClock.uptimeMillis() + LeftCarrierSwapPolicy.INTRO_MS
        handler.removeCallbacks(expiry)
        handler.postAtTime(expiry, until)
    }

    /** Whether the intro holds the label, given whether the carrier is currently shown. */
    fun active(carrierShown: Boolean): Boolean {
        if (armed && !carrierShown) {
            armed = false
            held = true
            snap = true
        }
        return held || SystemClock.uptimeMillis() < until
    }

    /** True once after a dark period or a screen-on without one: apply the location without a move. */
    fun takeSnap(): Boolean = snap.also { snap = false }

    fun reset() {
        handler.removeCallbacks(expiry)
        armed = false
        held = false
        until = 0L
        snap = false
    }
}

/** Per-lockscreen airplane bookkeeping, independent of the left clone container. */
internal class LeftKeyguardAirplaneState {
    var atLabel: Boolean? = null
    var animator: ValueAnimator? = null
    var travel: LeftPanelMotion? = null
    /** Canonical track: 0 = carrier label, 1 = icons; reversing never changes its endpoints. */
    var fraction = 0f
    val spring = AirplaneFlightSpring()
    var fallbackAtIcons = false
    /** The right-side container whose native airplane slot yields to the label. */
    var maskedContainer: ViewGroup? = null
    var nativeHidden: View? = null
    /** Stable per view, so a move keeps its endpoint identity across frames. */
    var endpoint: GlyphEndpoint? = null
}

/**
 * A view endpoint; its own opacity is its `transitionAlpha`. A native slot icon is recorded with
 * its appear amount at 1: one just unmasked draws nothing until the host's appear animation runs
 * (`StatusBarIconView.onDraw` scales by `mIconAppearAmount`), so a plain view capture is empty.
 */
private class ViewEndpoint(override val view: View, val native: Boolean) : GlyphEndpoint {
    private var recorded: Picture? = null
    private val frame = PanelGlyphFrame()
    private var recordedRevision = -1

    override var value: Float
        get() = view.transitionAlpha
        set(next) { if (view.transitionAlpha != next) view.transitionAlpha = next }

    override fun picture(): Picture? {
        if (!native) return null
        val image = view as? ImageView ?: return null
        val drawable = image.drawable ?: return null
        if (image.width <= 0 || image.height <= 0 || drawable.bounds.isEmpty) return null
        val appear = HostAccess.field(image.javaClass, APPEAR_FIELD)?.takeIf { it.type == Float::class.javaPrimitiveType }
            ?: return null
        val saved = appear.getFloat(image)
        try {
            appear.setFloat(image, 1f)
            if (!frame.captureView(image)) return null
            if (recorded == null || recordedRevision != frame.revision) {
                recorded = Picture(frame.picture)
                recordedRevision = frame.revision
            }
        } catch (error: Throwable) {
            DebugLog.w("IconTuner", "keyguard airplane capture failed", error)
            return null
        } finally {
            runCatching { appear.setFloat(image, saved) }
        }
        return recorded
    }

    private companion object {
        const val APPEAR_FIELD = "mIconAppearAmount"
    }
}

/**
 * Moves the lockscreen airplane between the carrier label's glyph and the icons' airplane: the
 * left airplane clone, else the airplane in Duo's lit lockscreen ring, else the host's native
 * slot. The side that does not hold it yields: the glyph through its span, Duo through its ring,
 * the native slot through a `RendererId.AIRPLANE` mask (a clone is hidden with its container).
 * A move runs a [LeftPanelMotion] along a straight track with a velocity-preserving spring.
 */
internal class LeftKeyguardAirplane(private val host: Host) {
    interface Host {
        val retiring: Boolean
        fun slotOf(view: View): String?
        /** Re-resolves the location soon, outside the current frame. */
        fun requestUpdate()
    }

    /** Main thread. [snap] applies the location without a move (bar hidden, screen just on). */
    fun update(state: KeyguardAirplaneScene, airplane: LeftKeyguardAirplaneState, target: LeftCarrierSwapPolicy.Target?,
               carrierShown: Boolean, iconsShown: Boolean, introActive: Boolean, snap: Boolean,
               labelKeepsAirplane: Boolean = false) {
        val carrier = state.keyguardCarrier ?: return
        val glyph = AirplaneGlyphTargets.keyguard(carrier, drawn = false)
        val besideText = glyph?.order?.let { it != AirplaneLabelGeometry.Order.GLYPH_ONLY } == true
        val other = other(state, airplane)
        val requested = LeftKeyguardAirplanePolicy.atLabel(glyph != null, besideText, carrierShown, target,
            iconsShown, introActive, duo = other != null && other !is ViewEndpoint, labelKeepsAirplane = labelKeepsAirplane)
        // A late Duo/native view is not permission to hide the only measured glyph.
        val next = if (requested == false && other == null && glyph != null) true else requested
        val endpointChanged = airplane.endpoint !== other
        val previous = airplane.atLabel
        if (endpointChanged) {
            cancel(airplane)
            airplane.endpoint?.value = 1f
            airplane.endpoint = other
        }
        if (next == previous && !snap) {
            if (airplane.animator == null) settle(state, airplane, next)
            return
        }
        airplane.atLabel = next
        // Keep the overlay and the current fraction on reversal; cancel only the animation clock.
        stopAnimator(airplane)
        DebugLog.i(TAG, "keyguard airplane location=${location(next)} from=${location(previous)} " +
            "icons=${kind(other)} snap=$snap fraction=${airplane.fraction}")
        if (snap || next == null || previous == null || other == null || !carrier.isShown ||
            !ValueAnimator.areAnimatorsEnabled() || endpointChanged && airplane.fraction > 0f) {
            endTravel(airplane)
            settle(state, airplane, next)
            return
        }
        val destination = if (next) 0f else 1f
        if (airplane.travel == null) airplane.fallbackAtIcons = airplane.fraction >= 1f
        // Unmask a native endpoint before measuring; its transition alpha still guards its drawing.
        if (other is ViewEndpoint && other.native) {
            unmask(airplane)
            other.value = if (airplane.fallbackAtIcons) 1f else 0f
            airplane.nativeHidden = other.view
        }
        airplane.spring.retarget(destination)
        val animator = ValueAnimator.ofFloat(0f, AirplaneFlightSpring.MAX_DURATION_MS.toFloat()).apply {
            duration = AirplaneFlightSpring.MAX_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (airplane.animator === it) {
                    val settled = airplane.spring.advance((it.animatedValue as Float).toLong())
                    guardedFrame(state, airplane, airplane.spring.position)
                    if (settled && airplane.animator === it) {
                        airplane.animator = null
                        it.cancel()
                        endTravel(airplane)
                        settle(state, airplane, airplane.atLabel)
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (airplane.animator !== animation) return
                    airplane.animator = null
                    endTravel(airplane)
                    settle(state, airplane, airplane.atLabel)
                }
            })
        }
        airplane.animator = animator
        animator.start()
    }

    /** Restores the native drawing of every endpoint; main thread only. */
    fun release(state: KeyguardAirplaneScene, airplane: LeftKeyguardAirplaneState) {
        airplane.atLabel = null
        cancel(airplane)
        settle(state, airplane, null)
        airplane.endpoint = null
    }

    private fun stopAnimator(airplane: LeftKeyguardAirplaneState) {
        airplane.animator?.let { animator ->
            airplane.animator = null
            animator.cancel()
        }
    }

    private fun cancel(airplane: LeftKeyguardAirplaneState) {
        stopAnimator(airplane)
        endTravel(airplane)
    }

    private fun guardedFrame(state: KeyguardAirplaneScene, airplane: LeftKeyguardAirplaneState, fraction: Float) {
        runCatching { frame(state, airplane, fraction) }.onFailure {
            cancel(airplane)
            airplane.atLabel = null
            settle(state, airplane, null)
            DebugLog.w(TAG, "keyguard airplane frame failed", it)
        }
    }

    private fun frame(state: KeyguardAirplaneScene, airplane: LeftKeyguardAirplaneState, fraction: Float) {
        airplane.fraction = fraction
        if (host.retiring) return
        val carrier = state.keyguardCarrier ?: return
        val glyph = AirplaneGlyphTargets.keyguard(carrier, drawn = false)
        val other = airplane.endpoint
        val root = carrier.rootView as? ViewGroup
        val glyphView = glyph?.view
        val otherView = other?.view
        val glyphPicture = glyph?.picture()
        val otherPicture = other?.picture()
        if (glyph == null || other == null || glyphView == null || otherView == null || root == null ||
            !root.isAttachedToWindow || !otherView.isAttachedToWindow || otherView.rootView !== root) {
            // An endpoint left mid-flight: give both back, then decide again from the views that remain
            // instead of leaving two airplanes until some unrelated refresh.
            release(state, airplane)
            host.requestUpdate()
            return
        }
        if (glyphPicture == null || other !is ViewEndpoint && otherPicture == null) {
            // Not measurable this frame: the departing side stays the visible copy.
            airplane.travel?.clear()
            airplane.travel = null
            glyph?.value = if (airplane.fallbackAtIcons && other != null) 0f else 1f
            other?.value = if (airplane.fallbackAtIcons) 1f else 0f
            return
        }
        val motion = airplane.travel ?: LeftPanelMotion {
            if (!host.retiring && airplane.animator != null) guardedFrame(state, airplane, airplane.fraction)
        }.also { airplane.travel = it }
        val ready = motion.update(root, glyphView, otherView, fraction, parkedTargetAlpha = 1f,
            sourcePicture = glyphPicture, targetPicture = otherPicture, targetAlpha = other,
            retainTargetUntilReady = airplane.fallbackAtIcons)
        glyph.value = if (!ready && airplane.fallbackAtIcons) 0f else LeftHandover.sourceAlpha(fraction, ready)
        if (!ready) other.value = if (airplane.fallbackAtIcons) 1f else 0f
    }

    private fun endTravel(airplane: LeftKeyguardAirplaneState) {
        airplane.travel?.clear()
        airplane.travel = null
    }

    /** The settled drawing for [atLabel]; a missing endpoint is left to its native owner. */
    private fun settle(state: KeyguardAirplaneScene, airplane: LeftKeyguardAirplaneState, atLabel: Boolean?) {
        val carrier = state.keyguardCarrier
        val glyph = carrier?.let { AirplaneGlyphTargets.keyguard(it, drawn = false) }
        glyph?.value = if (atLabel == false) 0f else 1f
        val other = airplane.endpoint
        airplane.fraction = if (atLabel == false) 1f else 0f
        airplane.spring.snap(airplane.fraction)
        val yielded = atLabel == true
        airplane.nativeHidden?.let { if (it.transitionAlpha != 1f) it.transitionAlpha = 1f }
        airplane.nativeHidden = null
        // A move hid its view source through transitionAlpha; hiding a settled view is the mask's job.
        when {
            other is ViewEndpoint && other.native -> {
                if (yielded) mask(state, airplane) else unmask(airplane)
                other.value = 1f
            }
            other is ViewEndpoint -> { unmask(airplane); other.value = 1f }
            other != null -> { unmask(airplane); other.value = if (yielded) 0f else 1f }
            else -> unmask(airplane)
        }
        // A ring that stopped drawing the airplane keeps no stale yield for the next airplane mode.
        if (!yielded) IconHostPorts.duo.keyguardAirplane(carrier?.rootView ?: return)?.value = 1f
    }

    private fun mask(state: KeyguardAirplaneScene, airplane: LeftKeyguardAirplaneState) {
        val container = state.airplaneContainer ?: return
        if (airplane.maskedContainer === container) return
        unmask(airplane)
        if (SlotMaskApplier.setAirplaneLabelMask(container, true)) airplane.maskedContainer = container
    }

    private fun unmask(airplane: LeftKeyguardAirplaneState) {
        val container = airplane.maskedContainer ?: return
        airplane.maskedContainer = null
        runCatching { SlotMaskApplier.setAirplaneLabelMask(container, false) }
            .onFailure { DebugLog.w(TAG, "keyguard airplane mask release failed", it) }
    }

    /** The icons' airplane: the left clone, else Duo's ring, else the host's native slot. */
    private fun other(state: KeyguardAirplaneScene, airplane: LeftKeyguardAirplaneState): GlyphEndpoint? {
        fun view(view: View, native: Boolean): GlyphEndpoint =
            (airplane.endpoint as? ViewEndpoint)?.takeIf { it.view === view && it.native == native }
                ?: ViewEndpoint(view, native)
        state.airplaneClone?.let { return view(it, native = false) }
        state.keyguardCarrier?.rootView?.let { root -> IconHostPorts.duo.keyguardAirplane(root) }
            ?.let { return it }
        val right = state.airplaneContainer ?: return null
        for (index in 0 until right.childCount) {
            val child = right.getChildAt(index) ?: continue
            if (host.slotOf(child) == IconSlotPolicy.SLOT_AIRPLANE) return view(child, native = true)
        }
        return null
    }

    private fun kind(other: GlyphEndpoint?): String = when {
        other == null -> "none"
        other is ViewEndpoint -> if (other.native) "native" else "left"
        else -> "duo"
    }

    private fun location(atLabel: Boolean?) = when (atLabel) {
        true -> "label"
        false -> "icons"
        null -> "native"
    }

    private companion object {
        const val TAG = "IconTuner"
    }
}
