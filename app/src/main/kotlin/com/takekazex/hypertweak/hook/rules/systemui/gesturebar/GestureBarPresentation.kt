package com.takekazex.hypertweak.hook.rules.systemui.gesturebar

import android.view.View
import android.view.animation.PathInterpolator
import com.takekazex.hypertweak.hook.base.HostAccess

/** Reversible View effects; the hook owner retains event and generation admission. */
internal class GestureBarPresentation(
    private val state: GestureBarState,
    private val views: () -> List<View>,
    private val isHandle: (View) -> Boolean,
    private val active: () -> Boolean,
    private val scheduleDeadline: (Int) -> Unit,
) {
    private companion object {
        const val PRIMARY_DISPLAY = 0
        const val FADE_OUT_MS = 133L
    }
    /**
     * Host alpha captured before the first suppression, so a reveal restores what the platform
     * wanted (an immersive app is not forced to alpha = 1) instead of assuming full opacity.
     */
    val hostAlphas = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Float>())

    /** Guards our own alpha writes from the setAlpha hook that records the host's value. */
    val ownAlphaWrite = ThreadLocal<Boolean>()

    /**
     * Displays whose handle is mid-fade. The draw gate must keep painting them for the duration:
     * suppressing [android.view.View.onDraw] at the same instant the alpha animation starts leaves
     * nothing to fade, so the bar vanishes instead of fading out.
     */
    val fadingDisplays = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /**
     * Views whose alpha is being written by our own animation. Without this the setAlpha hook would
     * record the animation's intermediate values as the host's alpha and destroy the value a later
     * reveal has to restore.
     */
    val selfDrivenAlpha =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    /** Stops drawing the handles of a display at once, without an animation of our own. */
    fun fadeImmediately(display: Int) {
        HostAccess.onMain {
            if (!active()) return@onMain
            fadingDisplays.remove(display)
            views()
                .filter { isHandle(it) && (it.display?.displayId ?: PRIMARY_DISPLAY) == display }
                .forEach { view ->
                    view.animate().cancel()
                    selfDrivenAlpha.remove(view)
                    hostAlphas.putIfAbsent(view, view.alpha)
                    setHandleAlpha(view, 0f)
                }
        }
    }

    /** Re-applies the stored suppression to live views after recovery or a settings change. */
    fun applyCurrentState() {
        HostAccess.onMain {
            if (!active()) return@onMain
            views().filter(isHandle).forEach { view ->
                val displayId = view.display?.displayId ?: PRIMARY_DISPLAY
                if (state.isSuppressed(displayId)) {
                    // Suppressed: remember the host alpha once, then keep the handle dark. A
                    // repaint here must never brighten a bar that belongs to Recents.
                    hostAlphas.putIfAbsent(view, view.alpha)
                    setHandleAlpha(view, 0f)
                } else if (displayId !in state.suppressedDisplays()) {
                    // Only restore when this display is genuinely not suppressed. Without the
                    // guard, a stale host alpha from a previous session is written back while
                    // Recents is still on screen, which is the bar reappearing at the bottom.
                    hostAlphas.remove(view)?.let { setHandleAlpha(view, it) }
                }
            }
            state.suppressedDisplays().forEach(scheduleDeadline)
        }
    }

    fun setHandleAlpha(view: View, alpha: Float) {
        ownAlphaWrite.set(true)
        try { view.alpha = alpha } finally { ownAlphaWrite.remove() }
    }

    /**
     * Applies the stored suppression to the handles of one display. A reveal restores the alpha
     * the host last asked for, so an immersive application is not forced to full opacity.
     */
    fun animateHandles(display: Int, fadeOut: Boolean) {
        HostAccess.onMain {
            if (!active()) return@onMain
            views()
                .filter { isHandle(it) && (it.display?.displayId ?: PRIMARY_DISPLAY) == display }
                .forEach { view ->
                    view.animate().cancel()
                    if (fadeOut) {
                        hostAlphas.putIfAbsent(view, view.alpha)
                        // The native token is still under the application, so this fades the real
                        // handle instead of creating a second one. The display stays drawable
                        // until the animation ends, otherwise there is no pill left to fade.
                        fadingDisplays.add(display)
                        selfDrivenAlpha.add(view)
                        // The native token is still under the application, so this fades the real
                        // handle instead of creating a second one.
                        view.animate().alpha(0f).setDuration(FADE_OUT_MS)
                            .setInterpolator(PathInterpolator(0.2f, 0f, 1f, 1f))
                            .withEndAction {
                                selfDrivenAlpha.remove(view)
                                // Only stop painting once every handle of this display is done.
                                val stillFading = views()
                                    .any { isHandle(it) && it in selfDrivenAlpha }
                                if (!stillFading) fadingDisplays.remove(display)
                            }
                            .start()
                    } else {
                        selfDrivenAlpha.remove(view)
                        hostAlphas.remove(view)?.let { setHandleAlpha(view, it) }
                    }
                }
            if (!fadeOut) fadingDisplays.remove(display)
        }
    }

    fun restoreHandles(displays: Set<Int>) {
        HostAccess.onMain {
            views().filter(isHandle).forEach { view ->
                val displayId = view.display?.displayId ?: PRIMARY_DISPLAY
                if (displayId in displays) hostAlphas.remove(view)?.let { setHandleAlpha(view, it) }
            }
        }
    }

}
