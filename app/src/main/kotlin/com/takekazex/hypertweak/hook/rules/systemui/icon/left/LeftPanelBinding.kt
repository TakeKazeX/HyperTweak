package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import com.takekazex.hypertweak.hook.rules.systemui.icon.HandoverCoordinator

import android.view.ViewGroup
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconRenderComponent
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.ShadeSwitchMotionPolicy
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference

/** Binds panel state and host rows to the left renderer's handover. */
internal class LeftPanelBinding(owner: BaseHooker) : IconRenderComponent(owner) {
    fun hookControlCenterHandover() {
        with(LeftRenderer) {
        CC_ROW_CLASSES.forEach { name ->
            val rowClass = name.toClassOrNull()
            if (rowClass == null) {
                DebugLog.hookSkipped(TAG, name, "class not found")
                return@forEach
            }
            rowClass.findMethodOrNull { name("onAttachedToWindow"); noParams() }?.let { method ->
                method.observe {
                    after { param -> (param.thisObject as? ViewGroup)?.let(::captureShadeRow) }
                }
            } ?: DebugLog.hookSkipped(TAG, "$name#onAttachedToWindow", "method not found")
        }

        panelProgressHooked = true
        PanelHub.subscribe(owner,
            beforeMotion = {
                if (active && !panelVisible) handover.parkHandover()
            },
            changed = {
                val switching = PanelHub.state.shadeSwitching
                if (switching && !shadeSwitchObserved) {
                    onShadeSwitchStarted(ShadeSwitchMotionPolicy.controlCenterAtRest(PanelHub.state.shadeSwitchProgress))
                } else if (!switching && shadeSwitchObserved) {
                    onShadeSwitchFinished(PanelHub.state.settledToControlCenter)
                }
                shadeSwitchObserved = switching
                if (!switching) onPanelProgress()
            }
        )

        }
    }

    /** Remembers a control-center row that draws the moved slots (weak: the shade is recreated). */
    fun captureShadeRow(row: ViewGroup) {
        with(LeftRenderer) {
        if (row.javaClass.name !in CC_ROW_CLASSES) return
        ccRows[row.javaClass.name] = WeakReference(row)
        // Install the parked copies immediately: a row that turns visible before the first expansion
        // callback (or attaches mid-gesture) must not draw the icon on the right.
        mainHandler.post { if (!retiring) handover.applyHandoverGuarded(panelProgress) }

        }
    }

    /** The live rows that draw a copy of the home cluster, in hand-over preference order. */
    fun liveControlCenterRows(): List<ViewGroup> {
        return with(LeftRenderer) {
        ccRowsScratch.clear()
        CC_ROW_CLASSES.forEach { name ->
            val row = ccRows[name]?.get() ?: return@forEach
            if (row.isAttachedToWindow) ccRowsScratch.add(row)
        }
        return ccRowsScratch

        }
    }

    fun onPanelProgress() {
        with(LeftRenderer) {
        if (panelProgress > 0f && !panelVisible) {
            // The first expansion callback can arrive after the fake row has already been made
            // visible. Park its copies before creating the overlay so that frame cannot flash the
            // original icon on the right.
            handover.parkHandover()
        }
        // The host drives this callback per frame while the shade moves, which is exactly when a
        // dropped frame can leave a clone behind. Arm the repair tick for the duration.
        if (panelVisible || panelProgress > 0f) scheduleReconcile()
        handover.applyHandoverGuarded(panelProgress)

        }
    }

    /**
     * Carries every selected icon through the same hand-over as Duo's middle Wi-Fi glyph.
     *
     * The host rows remain untouched geometrically. One overlay per slot draws the already tinted
     * left clone from its actual ink position to the expanded endpoint; duplicate fake-row copies
     * are alpha-suppressed only after the overlay has produced a frame. The moving glyph owns the
     * entire drag, then the native endpoint resumes at full arrival.
     */
    fun onShadeSwitchStarted(controlCenterVisible: Boolean) {
        with(LeftRenderer) {
        if (!active) return
        runCatching {
            handover.suspendHandoverForShadeSwitch()
            val sourceAlpha = if (controlCenterVisible) 0f else 1f
            synchronized(states) {
                states.values.forEach { state ->
                    if (state.isKeyguard) return@forEach
                    state.clones.values.forEach { clone -> HandoverCoordinator.alpha(clone, sourceAlpha) }
                }
            }
        }
            .onFailure { DebugLog.w(TAG, "LeftContainer shade-switch suspend failed", it) }

        }
    }

    fun onShadeSwitchFinished(controlCenterVisible: Boolean) {
        with(LeftRenderer) {
        if (!active) return
        if (controlCenterVisible) scheduleReconcile() else requestReconcile()
        handover.applyHandoverGuarded(panelProgress)

        }
    }

}
