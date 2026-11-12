package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import com.takekazex.hypertweak.hook.rules.systemui.icon.EndpointTargetLeases
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.icon.WifiPipelineAdapter
import com.takekazex.hypertweak.hook.rules.systemui.icon.left.LeftPanelMotion
import com.takekazex.hypertweak.hook.rules.systemui.icon.HandoverCoordinator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.IdentityHashMap
import com.takekazex.hypertweak.util.platform.ResourceLookup

internal class CarrierHandoverController(private val host: Host) {
    interface Host {
        val enabled: Boolean
        val statusBarId: Int
        val fakeStatusBarId: Int
        val wifiSignalId: Int
        val mobileSignalId: Int
        val mobileTypeId: Int
        val mobileGroupId: Int
        val activeDataSubId: Int
        fun blocks(): Collection<CarrierBlock>
        fun rowFor(view: View): CarrierRowParts?
        fun findStatusContainer(root: ViewGroup): Any?
    }
    private companion object { const val TAG = "IconTuner" }
    private val main = Handler(Looper.getMainLooper())
    private fun readInt(target: Any, name: String): Int? = runCatching { HostAccess.read(target, name) as? Int }.getOrNull()
    fun ownsTarget(view: View) = view in duoTargets || view in motions
    fun retireTarget(view: View) {
        if (view in motions) {
            // Removing a row can stop its last pre-draw; release the overlay/source now.
            // Other living rows reapply their source masks on the next pre-draw.
            releaseHandover()
        }
        duoTargets.retire(view)
    }
    fun retire() {
        releaseHandover()
        duoTargets.clear()
    }
    private val motions = IdentityHashMap<View, LeftPanelMotion>()
    private var progress = 0f
    private val duoTargets = EndpointTargetLeases(object : EndpointTargetLeases.Host<View, CarrierRowParts> {
        override fun parts(target: View) = host.rowFor(target)
        override fun ready(target: View, parts: CarrierRowParts) = when (target) {
            parts.wifi -> parts.wifiReady
            parts.type -> parts.cellularReady && !parts.typeSuppressed
            parts.signal -> parts.cellularReady
            else -> false
        }
        override fun usable(target: View) = isUsable(target)
        override fun network(target: View, parts: CarrierRowParts) = target === parts.type || target === parts.wifi
        override fun cellularReady(parts: CarrierRowParts) = parts.cellularReady
        override fun stopMotion(target: View) { motions.remove(target)?.clear() }
        override fun fadeLease(target: View, parts: CarrierRowParts, leased: Boolean) = parts.networkFade.lease(target as ImageView, leased)
        override fun alpha(target: View, value: Float) { HandoverCoordinator.alpha(target, value) }
    })
    private val endpointAlphas = CarrierEndpointAlphas<View>({ it.alpha }, { view, alpha -> HandoverCoordinator.alpha(view, alpha) })
    fun applyHandoverGuarded(value: Float) {
        runCatching { applyHandover(value) }
            .onFailure { DebugLog.w(TAG, "carrier hand-over failed", it) }
    }

    /** One overlay owns both endpoints until the expanded carrier row can take over. */
    private fun applyHandover(value: Float) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { applyHandoverGuarded(value) }
            return
        }
        val clamped = value.coerceIn(0f, 1f)
        progress = clamped
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
                IconHostPorts.positions.lowerRow(realIcons, fakeIcons, fakeRow.alpha, clamped)
                lowerRowUpdated = true
            } else IconHostPorts.positions.clearLowerRow()
            if (fakeRow == null || !fakeRow.isAttachedToWindow ||
                HandoverCoordinator.shade.hasActiveProxy(fakeRow)) continue
            // SystemUI moves the whole mirror, but it has no target in the two-row carrier
            // layout. Reuse its measured native glyphs for that last leg.
            for (parts in block.rows) {
                val model = parts.model
                if (parts.cellularReady && parts.signal.drawable != null) {
                    val bars = nativeSignal(fakeRow, model?.subId)
                    val composite = (parts.signal.drawable as? CarrierNativeGlyph)?.takeIf {
                        bars != null && it.children?.firstOrNull() === bars
                    }
                    move(parts, parts.signal, composite?.source ?: bars, clamped, used, sources, composite)
                }
                if (parts.wifiReady && parts.nativeWifi != null) {
                    move(parts, parts.wifi, WifiPipelineAdapter.nativeGlyph(fakeRow, host.wifiSignalId),
                        clamped, used, sources)
                }
                if (parts.cellularReady && !parts.typeSuppressed && parts.typeBitmap != null &&
                    parts.type !in duoTargets && parts.type.visibility == View.VISIBLE && isUsable(parts.type)) {
                    move(parts, parts.type, typeGlyph(fakeRow, model?.networkSubId), clamped, used, sources)
                }
            }
        }
        if (!lowerRowUpdated) IconHostPorts.positions.clearLowerRow()
        motions.keys.filterNot { it in used }.forEach(::releaseMotion)
        restoreEndpoints(sources)
    }

    private fun move(
        parts: CarrierRowParts, target: ImageView, source: View?, value: Float,
        used: MutableSet<View>, sources: MutableSet<View>, composite: CarrierNativeGlyph? = null
    ) {
        if (source == null || !isUsable(source) || target in duoTargets ||
            target.visibility != View.VISIBLE || !isUsable(target)) return
        val root = target.rootView as? ViewGroup ?: return
        leaseNetwork(parts, target, true)
        val motion = motions.getOrPut(target) { LeftPanelMotion { applyHandoverGuarded(progress) } }
        used += target
        val ready = motion.update(
            root, source, target, value,
            // The cellular endpoint is recolored for the carrier row even if it replays this view.
            sameArtwork = target.colorFilter == null && (target.drawable as? CarrierNativeGlyph)?.source === source,
            sourcePicture = composite?.picture
        )
        val components = composite?.children
        if (components == null) {
            sources += source
            endpointAlphas.suppress(source, ready)
        } else for (child in components) {
            sources += child
            endpointAlphas.suppress(child, ready)
        }
    }

    private fun releaseMotion(target: View) {
        motions.remove(target)?.clear()
        if (target !in duoTargets && target is ImageView) {
            host.rowFor(target)?.let { leaseNetwork(it, target, false) }
        }
    }

    private fun leaseNetwork(parts: CarrierRowParts, target: ImageView, active: Boolean) {
        if (target === parts.wifi || target === parts.type) parts.networkFade.lease(target, active)
    }

    fun releaseHandover() {
        IconHostPorts.positions.clearLowerRow()
        motions.keys.toList().forEach(::releaseMotion)
        restoreEndpoints()
        host.blocks().forEach { block ->
            block.rows.forEach { parts ->
                if (parts.wifi !in duoTargets && parts.type !in duoTargets) {
                    parts.networkFade.applyAlphas()
                }
            }
        }
    }

    private fun typeGlyph(row: ViewGroup, subId: Int?): View? {
        val mobile = subId?.let { mobileGroup(row, it) } ?: return null
        val single = ResourceLookup.identifier(mobile.resources, "mobile_type_single", "id", mobile.context.packageName)
        return glyph(mobile, single) ?: glyph(mobile, host.mobileTypeId)
    }

    private fun glyph(parent: ViewGroup, id: Int): View? = if (id == 0 || !parent.isShown) null else
        parent.findViewById<View>(id)?.takeIf { it.visibility == View.VISIBLE && isUsable(it) }

    // A masked holder can still supply the bars. Its network-type badge has a separate view.
    fun nativeSignal(root: ViewGroup, subId: Int?): View? {
        if (host.mobileSignalId == 0) return null
        val holder = subId?.let { mobileGroup(root, it) } ?: return null
        val signal = holder.findViewById<View>(host.mobileSignalId) as? ImageView ?: return null
        return signal.takeIf {
            it.drawable != null && it.visibility == View.VISIBLE && isUsable(it)
        }
    }

    fun inlineBadge(bars: View): View? {
        if (host.mobileTypeId == 0) return null
        val parent = bars.parent as? ViewGroup ?: return null
        if (parent.visibility != View.VISIBLE || !isUsable(parent)) return null
        val badge = parent.findViewById<View>(host.mobileTypeId) as? ImageView ?: return null
        return badge.takeIf {
            it.parent === parent && it.visibility == View.VISIBLE && it.drawable != null &&
                isUsable(it) && endpointAlphas.originalAlpha(it) > 0f
        }
    }

    private fun mobileGroup(root: ViewGroup, subId: Int): ViewGroup? {
        // The status-icon container holds one holder view per subscription; the mobile holder is
        // the one carrying that SIM's `subId` (the same boundary DuoRenderer reads).
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

    fun restoreEndpoints(retained: Set<View> = emptySet()) = endpointAlphas.restore(retained)

    fun acquireDuoTarget(target: View, owner: Any) = duoTargets.acquire(target, owner)
    fun releaseDuoTarget(target: View, owner: Any) = duoTargets.release(target, owner)
}
