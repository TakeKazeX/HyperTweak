package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.ControlCenterCarrierBlockHooker
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.WeakHashMap
import kotlin.math.abs

internal class LeftHandoverController(private val host: Host) {
    interface Host {
        val retiring: Boolean
        val active: Boolean
        val shadeSwitchSettledToControlCenter: Boolean
        val panelVisible: Boolean
        val panelProgress: Float
        fun sourceClones(): Map<String, View>?
        fun restoreSources()
        fun rows(): List<ViewGroup>
        fun slotOf(view: View): String?
    }
    private companion object {
        const val TAG = "IconTuner"
        const val CC_STATUS_ROW_CLASS = "com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon"
        const val CC_QS_HEADER_CLASS = "com.android.systemui.qs.MiuiQSHeaderView"
        const val CC_FAKE_STATUS_ROW_CLASS = "com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons"
        val CC_ROW_CLASSES = listOf(CC_STATUS_ROW_CLASS, CC_QS_HEADER_CLASS, CC_FAKE_STATUS_ROW_CLASS)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    fun cancelSlot(slot: String) { panelMotions.remove(slot)?.clear() }
    private class EndpointState(
        var savedAlpha: Float,
        var appliedAlpha: Float,
        var holdUntilReady: Boolean = false
    )

    /** One Duo-style overlay per moved slot; all operations are main-thread-only. */
    private val panelMotions = HashMap<String, LeftPanelMotion>()

    /** Duplicate fake/QS copies are alpha-owned only while the overlay is ready. */
    private val endpointStates = WeakHashMap<View, EndpointState>()

    private var statusIconsId = 0

    private fun applyHandover(progress: Float) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { if (!host.retiring) applyHandoverGuarded(progress) }
            return
        }
        // Policy changes can invalidate the source at any progress, including parked endpoints.
        if (!host.active || host.sourceClones().isNullOrEmpty()) {
            restoreHandover()
            return
        }
        if (ControlCenterCarrierBlockHooker.isShadeSwitching()) {
            suspendHandoverForShadeSwitch()
            return
        }
        val clamped = progress.coerceIn(0f, 1f)
        if (host.shadeSwitchSettledToControlCenter && clamped >= 0.999f) {
            settleNativeControlCenterEndpoint()
            return
        }
        if (clamped <= 0f) {
            if (host.panelVisible) parkHandover() else restoreHandover()
            return
        }

        val clones = host.sourceClones()
        val rows = host.rows()
        if (!host.active || clones == null || clones.isEmpty() || rows.isEmpty()) {
            restoreHandover()
            return
        }

        val usedSlots = HashSet<String>()
        val liveSecondaryEndpoints = HashSet<View>()
        clones.forEach { (slot, clone) ->
            val targets = rows.mapNotNull { statusRowIcon(it, slot) }
                .filter(::isUsableDestination)
                .distinct()
            // Prefer the real expanded control-center row. The fake/QS rows are only fallbacks for
            // layouts where that endpoint is not inflated yet.
            val target = targets.firstOrNull { it.parentRowClass() == CC_STATUS_ROW_CLASS }
                ?: targets.firstOrNull { it.parentRowClass() == CC_QS_HEADER_CLASS }
                ?: targets.firstOrNull()
            if (!clone.isAttachedToWindow || clone.visibility != View.VISIBLE || clone.width <= 0 || clone.height <= 0 ||
                target == null
            ) {
                if (clone.alpha != 1f) clone.alpha = 1f
                panelMotions.remove(slot)?.clear()
                return@forEach
            }

            // A view can have been parked while the panel was visible at progress=0. Carry its saved
            // host alpha into the motion, but keep its currently hidden value until the overlay draws.
            val parkedTargetAlpha = takeEndpointForMotion(target)
            val root = target.rootView as? ViewGroup
            if (root == null || !root.isAttachedToWindow) {
                if (clone.alpha != 1f) clone.alpha = 1f
                panelMotions.remove(slot)?.clear()
                return@forEach
            }

            val motion = panelMotions[slot] ?: LeftPanelMotion {
                if (!host.retiring) applyHandoverGuarded(host.panelProgress)
            }.also { panelMotions[slot] = it }
            val ready = motion.update(root, clone, target, clamped, parkedTargetAlpha)
            usedSlots += slot

            // Do not translate host children. Only duplicate copies are hidden after the overlay's
            // first successful draw; the selected target is owned by LeftPanelMotion.
            if (clamped < 1f) {
                targets.filterNot { it === target }.forEach { duplicate ->
                    liveSecondaryEndpoints += duplicate
                    suppressEndpoint(duplicate, ready)
                }
            }
            clone.alpha = LeftHandover.sourceAlpha(clamped, ready)
        }

        panelMotions.entries.removeIf { (slot, motion) ->
            if (slot in usedSlots) false else { motion.clear(); true }
        }
        releaseEndpointStates(liveSecondaryEndpoints)
        if (clamped >= 1f) releaseEndpointStates(emptySet())
    }

    /** The host callback runs inside a frame; never let a failure in here reach SystemUI. */
    fun applyHandoverGuarded(progress: Float) {
        runCatching { applyHandover(progress) }
            .onFailure { DebugLog.w(TAG, "LeftContainer hand-over failed", it) }
    }

    /** Keep native QS/QS_FAKE copies as the only moving glyphs during a shade switch. */
    fun suspendHandoverForShadeSwitch() {
        if (panelMotions.isNotEmpty()) {
            panelMotions.values.forEach(LeftPanelMotion::clear)
            panelMotions.clear()
        }
        releaseEndpointStates(emptySet())
    }

    private fun settleNativeControlCenterEndpoint() {
        suspendHandoverForShadeSwitch()
        val clones = host.sourceClones() ?: return
        if (!host.active) return
        val rows = host.rows()
        clones.forEach { (slot, clone) ->
            val ready = rows.any { row -> statusRowIcon(row, slot)?.let(::isUsableDestination) == true }
            val alpha = if (ready) 0f else 1f
            if (clone.alpha != alpha) clone.alpha = alpha
        }
    }

    /** Candidate endpoint must be attached, visible, and measured. */
    private fun isUsableDestination(view: View): Boolean =
        view.isAttachedToWindow && com.takekazex.hypertweak.hook.rules.systemui.icon.signalEndpointVisible(view, view.rootView)

    /** The row class is resolved from the captured ancestor rather than from an icon name. */
    private fun View.parentRowClass(): String? =
        CC_ROW_CLASSES.firstOrNull { name -> ancestorOf(name) != null }

    private fun View.ancestorOf(name: String): View? = HostAccess.ancestor(this, name, includeSelf = false)

    private fun suppressEndpoint(view: View, ready: Boolean, holdUntilReady: Boolean = false) {
        val entry = endpointStates[view] ?: EndpointState(view.alpha, view.alpha)
            .also { endpointStates[view] = it }
        if (holdUntilReady) entry.holdUntilReady = true
        if (abs(view.alpha - entry.appliedAlpha) > .001f) entry.savedAlpha = view.alpha
        val alpha = if (ready || entry.holdUntilReady) 0f else entry.savedAlpha
        if (view.alpha != alpha) view.alpha = alpha
        entry.appliedAlpha = alpha
    }

    /** Removes a parked duplicate without revealing it; [LeftPanelMotion] owns it next. */
    private fun takeEndpointForMotion(view: View): Float? {
        val entry = endpointStates.remove(view) ?: return null
        if (abs(view.alpha - entry.appliedAlpha) > .001f) entry.savedAlpha = view.alpha
        if (view.alpha != entry.appliedAlpha) view.alpha = entry.appliedAlpha
        return entry.savedAlpha
    }

    /** Restore only values still owned by us, matching DuoPanelMotion's fail-open cleanup. */
    private fun releaseEndpointStates(live: Set<View>) {
        if (endpointStates.isEmpty()) return
        val iterator = endpointStates.entries.iterator()
        while (iterator.hasNext()) {
            val (view, entry) = iterator.next()
            if (view in live) continue
            if (abs(view.alpha - entry.appliedAlpha) < .001f) view.alpha = entry.savedAlpha
            iterator.remove()
        }
    }

    /** Gives every moved slot back to the left container (closed shade, feature off, teardown). */
    fun restoreHandover() {
        panelMotions.values.forEach(LeftPanelMotion::clear)
        panelMotions.clear()
        releaseEndpointStates(emptySet())
        host.restoreSources()
    }

    /**
     * Progress can reach zero before the user lifts the finger. Keep the left clone as the visible
     * owner and park every currently inflated control-center copy until the host reports dismissal.
     */
    fun parkHandover() {
        panelMotions.values.forEach(LeftPanelMotion::clear)
        panelMotions.clear()
        releaseEndpointStates(emptySet())
        host.restoreSources()
        val clones = host.sourceClones() ?: return
        if (!host.active || clones.isEmpty()) return
        host.rows().forEach { row ->
            clones.keys.forEach { slot ->
                statusRowIcon(row, slot)?.takeIf(::isUsableDestination)?.let { duplicate ->
                    suppressEndpoint(duplicate, ready = false, holdUntilReady = true)
                }
            }
        }
    }

    private fun statusRowIcon(row: ViewGroup?, slot: String): View? {
        val icons = row?.let(::statusIconsContainer) ?: return null
        for (index in 0 until icons.childCount) {
            val child = icons.getChildAt(index)
            if (child != null && host.slotOf(child) == slot) return child
        }
        return null
    }

    /** `R.id.statusIcons` of a control-center row; resolved through the row's own resources. */
    private fun statusIconsContainer(row: ViewGroup): ViewGroup? {
        if (statusIconsId == 0) {
            statusIconsId = row.resources.getIdentifier("statusIcons", "id", "com.android.systemui")
        }
        val id = statusIconsId
        if (id == 0) return null
        return row.findViewById<View>(id) as? ViewGroup
    }

}
