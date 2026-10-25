package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.DebugLog
import java.util.IdentityHashMap
import kotlin.math.abs

internal class CarrierHandoverController(private val host: Host) {
    interface Host {
        val enabled: Boolean
        val artworkReady: Boolean
        val statusBarId: Int
        val fakeStatusBarId: Int
        val wifiSignalId: Int
        val mobileTypeId: Int
        val mobileGroupId: Int
        fun blocks(): Collection<CarrierBlock>
        fun rowFor(view: View): CarrierRowParts?
        fun findStatusContainer(root: ViewGroup): Any?
    }
    private companion object { const val TAG = "IconTuner" }
    private val main = Handler(Looper.getMainLooper())
    private fun readInt(target: Any, name: String): Int? = runCatching { HostAccess.read(target, name) as? Int }.getOrNull()
    fun ownsTarget(view: View) = view in duoTargets
    fun retireTarget(view: View) { duoTargets.retire(view) }
    fun retire() {
        motions.values.forEach(CarrierTypeMotion::clear)
        motions.clear()
        duoTargets.clear()
        restoreEndpoints()
    }
    private val motions = IdentityHashMap<View, CarrierTypeMotion>()
    private val duoTargets = SignalTargetOwnership<View>()
    private val endpointStates = IdentityHashMap<View, EndpointState>()
    fun applyHandoverGuarded(value: Float) {
        runCatching { applyHandover(value) }
            .onFailure { DebugLog.w(TAG, "carrier hand-over failed", it) }
    }

    /**
     * Carries the trailing network glyph through the same hand-over as Duo's middle Wi-Fi layer:
     * expansion moves the native status-bar source into the label row. Appearance opacity follows
     * the host fake-row/carrier-layout crossfade, preserving the source palette while dragging.
     * The source copy is alpha-suppressed only after the overlay has produced a frame.
     */
    private fun applyHandover(value: Float) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { applyHandoverGuarded(value) }
            return
        }
        val clamped = value.coerceIn(0f, 1f)
        if (!host.enabled) {
            releaseHandover()
            return
        }
        if (clamped <= 0f || clamped >= 1f) {
            // Shade switching can report only an endpoint. Do not start a second module-owned
            // animation there: the host is already animating the header and native status row.
            releaseHandover()
            return
        }
        if (!host.artworkReady) { releaseHandover(); return }
        drawHandover(clamped)
    }

    private fun drawHandover(clamped: Float) {
        val used = HashSet<View>()
        val sources = HashSet<View>()
        var lowerRowUpdated = false
        for (block in host.blocks().toList()) {
            if (!block.compact || !block.layout.isShown) continue
            val header = block.layout.parent as? ViewGroup
            val realRow = header?.findViewById<ViewGroup>(host.statusBarId)
            val fakeRow = header?.findViewById<ViewGroup>(host.fakeStatusBarId)
            val realIcons = realRow?.let { host.findStatusContainer(it) as? ViewGroup }
            val fakeIcons = fakeRow?.let { host.findStatusContainer(it) as? ViewGroup }
            // fakeRow is non-null here: fakeIcons derives from it through ?.let.
            if (realIcons != null && fakeIcons != null) {
                IconPositionHooker.updateLowerRowHandover(realIcons, fakeIcons, fakeRow.alpha, clamped)
                lowerRowUpdated = true
            } else IconPositionHooker.clearLowerRowHandover()
            for (parts in block.rows) {
                val model = parts.model ?: continue
                val entries = ArrayList<Pair<ImageView, View?>>(2)
                parts.wifiBitmap?.takeIf { parts.wifiReady }?.let {
                    entries += parts.wifi to sourceGlyph(block, wifi = true, subId = null)
                }
                // A suppressed type stays out of the hand-over: its glyph is faded out on purpose,
                // and the overlay would otherwise draw it at full alpha while the panel opens.
                parts.typeBitmap?.takeIf { parts.cellularReady && !parts.typeSuppressed }?.let {
                    entries += parts.type to sourceGlyph(block, wifi = false, subId = model.subId)
                }
                for ((target, source) in entries) {
                    if (target in duoTargets || target.visibility != View.VISIBLE || target.width <= 0 || target.height <= 0) continue
                    if (source == null) {
                        motions.remove(target)?.clear()
                        parts.networkFade.lease(target, false)
                        continue
                    }
                    val root = target.rootView as? ViewGroup ?: continue
                    val motion = motions[target] ?: CarrierTypeMotion().also { motions[target] = it }
                    parts.networkFade.lease(target, true)
                    val ready = motion.update(root, source, target, clamped, sourceAppearanceAlpha(source))
                    used += target
                    sources += source
                    // The carrier layout already receives the native appearance fade. A second
                    // progress-based fade would switch palettes while the finger is still moving.
                    target.alpha = 1f
                    suppressEndpoint(source, ready)
                }
            }
        }
        if (!lowerRowUpdated) IconPositionHooker.clearLowerRowHandover()
        motions.entries.removeIf { (view, motion) ->
            if (view in used) false else {
                motion.clear()
                val parts = host.rowFor(view)
                if (view !in duoTargets) {
                    if (parts != null) parts.networkFade.lease(view as ImageView, false)
                    else view.alpha = 0f
                }
                true
            }
        }
        restoreEndpoints(sources)
    }

    fun releaseHandover() {
        IconPositionHooker.clearLowerRowHandover()
        motions.forEach { (view, motion) ->
            motion.clear()
            host.rowFor(view)?.networkFade?.lease(view as ImageView, false)
        }
        motions.clear()
        restoreEndpoints()
        host.blocks().forEach { block ->
            block.rows.forEach { parts ->
                if (parts.wifi !in duoTargets && parts.type !in duoTargets) {
                    parts.networkFade.applyAlphas()
                }
            }
        }
    }

    /**
     * The glyph the user is looking at while the panel is still closed: the collapsed mirror of the
     * status bar inside the shade header (`ControlCenterFakeStatusIcons`), which carries the same
     * bound icons as the home bar.
     */
    private fun sourceGlyph(block: CarrierBlock, wifi: Boolean, subId: Int?): View? {
        if (host.fakeStatusBarId == 0) return null
        val header = block.layout.parent as? ViewGroup ?: return null
        // The fake row is the visible stand-in while the panel is dragged.
        val fakeRow = header.findViewById<View>(host.fakeStatusBarId) as? ViewGroup
        if (fakeRow != null && fakeRow.isAttachedToWindow &&
            !SignalHandoverEvents.shade.hasActiveProxy(fakeRow)
        ) {
            glyphIn(fakeRow, wifi, subId)?.let { return it }
        }
        // The expanded row has its own palette. If the status-bar mirror is unavailable,
        // leave the native appearance transition in charge instead of inventing a source color.
        return null
    }

    /** The fake row is the native color/opacity owner during the drag, even when alpha-masked. */
    private fun sourceAppearanceAlpha(source: View): Float {
        var parent = source.parent as? View
        while (parent != null) {
            if (parent.id == host.fakeStatusBarId) return parent.alpha
            parent = parent.parent as? View
        }
        return 1f
    }

    private fun glyphIn(row: ViewGroup, wifi: Boolean, subId: Int?): View? {
        if (wifi) return row.findViewById<View>(host.wifiSignalId)?.takeIf(::isUsable)
        val group = mobileGroup(row, subId ?: return null) ?: return null
        return group.findViewById<View>(host.mobileTypeId)?.takeIf(::isUsable)
    }

    private fun mobileGroup(root: ViewGroup, subId: Int): ViewGroup? {
        // The status-icon container holds one holder view per subscription; the mobile holder is
        // the one carrying that SIM's `subId` (the same boundary DuoSignalHooker reads).
        val container = host.findStatusContainer(root) as? ViewGroup
        if (container != null) {
            for (index in 0 until container.childCount) {
                val child = container.getChildAt(index) as? ViewGroup ?: continue
                if (readInt(child, "subId") == subId) return child
            }
        }
        if (host.mobileGroupId == 0) return null
        val found = root.findViewById<View>(host.mobileGroupId) as? ViewGroup ?: return null
        return if (readInt(found, "subId") == subId) found else null
    }

    /**
     * A hand-over source is a geometric anchor, not something that has to be on screen: at rest the
     * real control-center row's glyph is already masked (invisible) by the time the settle ramp runs,
     * and its last frame is exactly where the travel has to start from.
     */
    fun isUsable(view: View): Boolean =
        view.isAttachedToWindow && view.width > 0 && view.height > 0

    private fun suppressEndpoint(view: View, ready: Boolean) {
        val entry = endpointStates[view] ?: EndpointState(view).also { endpointStates[view] = it }
        if (abs(view.alpha - entry.appliedAlpha) > .001f) entry.savedAlpha = view.alpha
        val alpha = if (ready) 0f else entry.savedAlpha
        if (view.alpha != alpha) view.alpha = alpha
        entry.appliedAlpha = alpha
    }

    fun restoreEndpoints(retained: Set<View> = emptySet()) {
        if (endpointStates.isEmpty()) return
        val iterator = endpointStates.entries.iterator()
        while (iterator.hasNext()) {
            val (view, entry) = iterator.next()
            if (view in retained) continue
            if (abs(view.alpha - entry.appliedAlpha) < .001f) view.alpha = entry.savedAlpha
            iterator.remove()
        }
    }

    fun acquireDuoTarget(target: View, owner: Any): Boolean {
        val parts = host.rowFor(target) ?: return false
        val ready = when (target) {
            parts.wifi -> parts.wifiReady
            parts.type -> parts.cellularReady && !parts.typeSuppressed
            parts.signal -> parts.cellularReady
            else -> false
        }
        if (!ready || !isUsable(target) || (target in duoTargets && !duoTargets.owns(target, owner))) return false
        if (duoTargets.owns(target, owner)) return true
        motions.remove(target)?.clear()
        if (target === parts.type || target === parts.wifi) {
            parts.networkFade.lease(target, true)
        }
        target.alpha = 1f
        check(duoTargets.acquire(target, owner))
        return true
    }

    fun releaseDuoTarget(target: View, owner: Any) {
        if (!duoTargets.owns(target, owner)) return
        duoTargets.release(target, owner)
        val parts = host.rowFor(target)
        if (parts != null && (target === parts.wifi || target === parts.type)) {
            parts.networkFade.lease(target, false)
        } else {
            target.alpha = if (parts?.cellularReady == true) 1f else 0f
        }
    }

}
