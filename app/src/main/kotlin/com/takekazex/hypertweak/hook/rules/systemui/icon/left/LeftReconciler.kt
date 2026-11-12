package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import android.os.Looper
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconHostPorts
import com.takekazex.hypertweak.util.logging.DebugLog

/** Refreshes retained views and schedules repair while panel motion is active. */
internal class LeftReconciler {
    fun reconcileTick() {
        with(LeftRenderer) {
        if (retiring) return
        runCatching { reconcileAll() }.onFailure { t ->
            DebugLog.w(TAG, "LeftContainer reconcile failed", t)
        }
        // Keep repairing while the shade is on screen or still moving; stop once it settles.
        if (panelVisible || panelProgress > 0f) scheduleReconcile()

        }
    }

    /**
     * Reconciles now, for an event that changed the outcome. This is what replaces the old
     * unconditional timer when the shade is closed: settings commits, clone attach and Duo
     * ownership all land here.
     */
    fun requestReconcile() {
        with(LeftRenderer) {
        if (retiring) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { if (!retiring) requestReconcile() }
            return
        }
        mainHandler.removeCallbacks(reconcileRunnable)
        runCatching { reconcileAll() }
            .onFailure { DebugLog.w(TAG, "LeftContainer reconcile failed", it) }
        if (panelVisible || panelProgress > 0f) scheduleReconcile()

        }
    }

    fun scheduleReconcile() {
        with(LeftRenderer) {
        mainHandler.removeCallbacks(reconcileRunnable)
        mainHandler.postDelayed(reconcileRunnable, RECONCILE_INTERVAL_MS)

        }
    }

    fun onDuoOwnershipChanged() {
        with(LeftRenderer) {
        if (ownershipRefreshPending) return
        ownershipRefreshPending = true
        mainHandler.post {
            if (retiring) return@post
            ownershipRefreshPending = false
            reconcileAll()
        }

        }
    }

    fun reconcileAll() {
        with(LeftRenderer) {
        if (retiring || reconciling) return
        reconciling = true
        try { reconcilePresentation() } finally { reconciling = false }

        }
    }

    fun reconcilePresentation() {
        with(LeftRenderer) {
        reloadSnapshot()
        var needsOverlayRefresh = false
        synchronized(states) {
            states.values.forEach { state ->
                val next = visibility(state)
                if (state.presentation != next) {
                    DebugLog.i(TAG, "left visibility surface=${if (state.isKeyguard) "keyguard" else "home"} " +
                        "root=${System.identityHashCode(state.barRoot)} reason=$next clones=${state.clones.size}")
                }
                state.presentation = next
                if (next.retain) {
                    // Content and migration ownership remain current even while temporarily hidden.
                    presentation.applyVisibility(state)
                    presentation.syncClones(state)
                } else {
                    presentation.teardownState(state)
                }
                // The lockscreen airplane also moves when left placement is off or Duo leaves no clones.
                if (state.isKeyguard) carrierSwap.updateAirplane(state)
            }
            // A merged list only changes when the owned slots do, so react to that instead of
            // re-publishing on every tick (the host asserts the main thread and re-measures).
            val home = homeOwnedSlots()
            val keyguard = keyguardOwnedSlots()
            if (home != publishedOwnedSlots || keyguard != publishedKeyguardOwnedSlots) {
                publishedOwnedSlots = home
                publishedKeyguardOwnedSlots = keyguard
                needsOverlayRefresh = true
            }
        }
        if (needsOverlayRefresh) {
            IconHostPorts.duo.releaseStatusIcons(homeOwnedSlots())
            IconHostPorts.republish()
        }
        // A repair tick must retain a held partial drag. The coarse fully-open signal is only a
        // fallback on hosts where the per-frame expansion callback could not be resolved.
        shadeFullyOpen = readShadeFullyOpen(
            synchronized(states) { states.values.firstOrNull { !it.isKeyguard }?.barRoot }
        )
        handover.applyHandoverGuarded(LeftHandover.repairProgress(panelProgress, panelProgressHooked, shadeFullyOpen))

        }
    }

}
