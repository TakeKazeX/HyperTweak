package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import com.takekazex.hypertweak.hook.rules.systemui.icon.SignalHandoverEvents
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.takekazex.hypertweak.util.logging.DebugLog

/** Owns the borrowed host tree and clone presentation; the parent sequences hook lifecycle. */
internal class LeftPresentation(private val host: Host, private val cloner: LeftIconCloner) {
    interface Host {
        val activeSlots: Set<String>
        fun cancelSlot(slot: String)
        fun slotOf(view: View): String?
    }
    private companion object {
        const val TAG = "IconTuner"
        const val CONTAINER_TAG = "hypertweak_left_container"
        const val WRAPPER_TAG = "hypertweak_keyguard_left_wrapper"
    }
    private fun ensureContainer(state: LeftState): LinearLayout? {
        state.leftContainer?.let { return it }
        if (state.isKeyguard) ensureKeyguardAnchorWrapper(state) ?: return null
        val left = LinearLayout(state.leftHost.context)
        left.orientation = LinearLayout.HORIZONTAL
        // The container's OWN gravity aligns its children vertically. This must be
        // CENTER_VERTICAL: LinearLayout defaults to TOP, which hugged the icon clones
        // against the top of the full-height container — the "被抬高" (raised) look.
        // (`layoutParams.gravity` below is only how the HOST positions this container,
        // and is irrelevant because the container already fills the host height.)
        left.gravity = android.view.Gravity.CENTER_VERTICAL
        left.clipChildren = false
        left.clipToPadding = false
        left.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        left.tag = CONTAINER_TAG
        left.visibility = if (state.islandShowing) View.GONE else View.VISIBLE
        val parent = if (state.isKeyguard) state.anchorWrapper else state.leftHost
        val index = if (state.isKeyguard) {
            parent?.childCount ?: return null
        } else {
            state.leftHost.indexOfChild(state.clock) + 1
        }
        parent?.addView(left, index)
        state.leftContainer = left
        DebugLog.i(TAG, "LeftContainer attached")
        return left
    }

    /**
     * The lockscreen's clock and alarm are FrameLayout children, not a horizontal status-bar
     * container. Keep their original parent/index/layout params and wrap only those host views,
     * then append the module clone container after them.
     */
    private fun ensureKeyguardAnchorWrapper(state: LeftState): LinearLayout? {
        state.anchorWrapper?.let { return it }
        val entries = state.anchorEntries
        if (entries.isEmpty() || entries.any { it.view.parent !== it.parent }) return null
        val parent = entries.first().parent
        val insertAt = entries.minOf { it.index }.coerceIn(0, parent.childCount)
        val wrapper = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_INHERIT
            clipChildren = false
            clipToPadding = false
            tag = WRAPPER_TAG
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        entries.sortedBy { it.index }.forEach { entry ->
            parent.removeView(entry.view)
        }
        parent.addView(wrapper, insertAt)
        entries.sortedBy { it.index }.forEach { entry ->
            wrapper.addView(entry.view, entry.layoutParams)
        }
        state.anchorWrapper = wrapper
        return wrapper
    }

    fun teardownState(state: LeftState) {
        removeClones(state)
        state.leftContainer?.let { left ->
            runCatching { (left.parent as? ViewGroup)?.removeView(left) }
            state.leftContainer = null
        }
        state.clones.clear()
        state.migratedSlots = emptySet()
        restoreKeyguardAnchors(state)
    }

    private fun restoreKeyguardAnchors(state: LeftState) {
        val wrapper = state.anchorWrapper ?: return
        val parent = state.anchorEntries.firstOrNull()?.parent ?: return
        runCatching {
            state.anchorEntries.forEach { entry ->
                if (entry.view.parent === wrapper) wrapper.removeView(entry.view)
            }
            if (wrapper.parent === parent) parent.removeView(wrapper)
            state.anchorEntries.sortedBy { it.index }.forEach { entry ->
                if (entry.view.parent == null) {
                    parent.addView(entry.view, entry.index.coerceIn(0, parent.childCount), entry.layoutParams)
                }
            }
        }.onFailure { DebugLog.w(TAG, "failed to restore keyguard clock/alarm placement", it) }
        state.anchorWrapper = null
    }

    /** Idempotent: sees the current right-cluster children and mirrors them into the left. */
    fun syncClones(state: LeftState) {
        val right = state.rightContainer
        val duoOwnsAirplane = SignalHandoverEvents.shade.ownsAirplane(right)
        val slots = if (duoOwnsAirplane) host.activeSlots - "airplane" else host.activeSlots
        // 1. Drop clones whose slot is no longer selected or has no live view on the right.
        val it = state.clones.entries.iterator()
        while (it.hasNext()) {
            val (slot, clone) = it.next()
            val child = rightChildForSlot(state, slot)
            if (slot !in slots || child == null) {
                if (slot == "airplane" && duoOwnsAirplane) {
                    cloner.cancel(clone)
                    host.cancelSlot(slot)
                } else {
                    cloner.animateCloneVisibility(clone, false)
                    if (cloner.isAnimating(clone)) continue
                }
                it.remove()
                runCatching { (clone.parent as? ViewGroup)?.removeView(clone) }
                cloner.forget(clone)
                cloner.unregisterDarkReceiver(state.manager, clone)
            }
        }

        // 2. Create / refresh clones in right-container order.
        var targetIndex = 0
        for (i in 0 until right.childCount) {
            val child = right.getChildAt(i)
            if (child == null) continue
            val slot = host.slotOf(child) ?: continue
            if (slot !in slots) continue
            val existing = state.clones[slot]
            val clone = existing ?: createClone(state, child, slot) ?: continue
            if (!cloner.updateClone(clone, child)) {
                state.clones.remove(slot)
                runCatching { (clone.parent as? ViewGroup)?.removeView(clone) }
                cloner.forget(clone)
                cloner.unregisterDarkReceiver(state.manager, clone)
                continue
            }
            if (existing == null) {
                state.clones[slot] = clone
                cloner.registerDarkReceiver(state.manager, clone)
            }
            val container = state.leftContainer ?: continue
            if (container.indexOfChild(clone) != targetIndex) {
                container.removeView(clone)
                container.addView(clone, targetIndex.coerceAtMost(container.childCount))
            }
            targetIndex++
        }
        if (state.clones.isEmpty()) {
            // Do not leave an empty marked container (or a keyguard anchor wrapper) behind while
            // the source icon is still unavailable. The next host icon event can retry cleanly.
            teardownState(state)
            return
        }
        state.migratedSlots = state.clones.keys.toSet()
        // Home is intentionally hidden while the keyguard owns the status bar. A normal unlock
        // reaches this path with the same state and clones, so restore the container explicitly;
        // otherwise the clones remain permanently GONE until SystemUI is recreated.
        state.leftContainer?.let { left ->
            left.visibility = if (state.islandShowing) View.GONE else View.VISIBLE
        }
    }

    private fun createClone(state: LeftState, child: View, slot: String): View? {
        val clone = cloner.createClone(state.leftHost.context, slot) ?: return null
        val container = ensureContainer(state) ?: return null
        container.addView(clone)
        return clone
    }

    private fun rightChildForSlot(state: LeftState, slot: String): View? {
        val right = state.rightContainer
        for (i in 0 until right.childCount) {
            val child = right.getChildAt(i)
            if (host.slotOf(child) == slot) return child
        }
        return null
    }

    fun removeClones(state: LeftState) {
        state.clones.values.forEach { clone ->
            runCatching { (clone.parent as? ViewGroup)?.removeView(clone) }
            cloner.forget(clone)
            cloner.unregisterDarkReceiver(state.manager, clone)
        }
        state.clones.clear()
    }
}
