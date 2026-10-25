package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.content.Context
import android.graphics.Matrix
import android.graphics.Picture
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterCarrierBlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterHeaderHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalState
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.DebugLog

/** Owns proxy transforms, signal overlays and network/airplane handover leases. */
internal class DuoPanelHandover(private val host: Host) {
    interface Host {
        val panelStretchHeight: Float
        val panelProgress: Float
        val panelVisible: Boolean
        val expandedStyle: DuoExpandedStyle
        val shadeSwitchSettledToControlCenter: Boolean
        val small5GaEnabled: Boolean
        val componentSizes: DuoSizes
        val enabled: Boolean
        val mobile: MobileSignalState
        fun bindings(): Collection<DuoBinding>
        fun owns(binding: DuoBinding): Boolean
        fun slotIndex(subId: Int): Int?
        fun destinationSignal(context: Context, level: Int): Picture?
    }
    private companion object {
        const val TAG = "DuoSignal"
        const val AIRPLANE_SLOT = "airplane"
    }
    private val main = Handler(Looper.getMainLooper())
    private fun read(target: Any, name: String) = HostAccess.read(target, name)
    private fun ancestor(view: View, name: String) = HostAccess.ancestor(view, name)
    private val panelMatrix = Matrix()
    private val inversePanelMatrix = Matrix()
    private val panelPoint = FloatArray(2)

    private fun screenAnchor(view: View): DuoPanelPoint {
        panelMatrix.reset()
        view.transformMatrixToGlobal(panelMatrix)
        panelPoint[0] = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) 0f else view.width.toFloat()
        panelPoint[1] = view.height / 2f
        panelMatrix.mapPoints(panelPoint)
        return DuoPanelPoint(panelPoint[0], panelPoint[1])
    }

    private fun screenVector(view: View, x: Float, y: Float): DuoPanelPoint {
        panelMatrix.reset()
        (view.parent as? View)?.transformMatrixToGlobal(panelMatrix)
        panelPoint[0] = x; panelPoint[1] = y
        panelMatrix.mapVectors(panelPoint)
        return DuoPanelPoint(panelPoint[0], panelPoint[1])
    }

    fun restoreProxyPosition(binding: DuoBinding) {
        binding.proxyRoot?.let { root ->
            root.translationX = binding.proxyX.restore(root.translationX)
            root.translationY = binding.proxyY.restore(root.translationY)
        }
        binding.proxyRoot = null
    }

    private fun positionProxy(binding: DuoBinding, root: View, home: View, target: View,
                              expandedRoot: View, progress: Float) {
        if (binding.proxyRoot !== root) restoreProxyPosition(binding)
        val translated = screenAnchor(target)
        val translation = screenVector(expandedRoot, expandedRoot.translationX, expandedRoot.translationY)
        val endpoint = DuoPanelPoint(translated.x - translation.x, translated.y - translation.y)
        val stretch = screenVector(expandedRoot, 0f, -host.panelStretchHeight * (1f - progress))
        val desired = DuoPanelGeometry.position(screenAnchor(home), endpoint, progress, stretch)
        val actual = screenAnchor(binding.view)
        panelMatrix.reset()
        (root.parent as? View)?.transformMatrixToGlobal(panelMatrix)
        if (!panelMatrix.invert(inversePanelMatrix)) { restoreProxyPosition(binding); return }
        panelPoint[0] = desired.x - actual.x; panelPoint[1] = desired.y - actual.y
        inversePanelMatrix.mapVectors(panelPoint)
        binding.proxyRoot = root
        root.translationX = binding.proxyX.apply(root.translationX, panelPoint[0])
        root.translationY = binding.proxyY.apply(root.translationY, panelPoint[1])
    }

    fun hideExpandedDots(binding: DuoBinding): Boolean =
        binding.surface != DuoSurface.HOME && binding.surface != DuoSurface.KEYGUARD &&
        host.expandedStyle == DuoExpandedStyle.KEEP_DUO &&
        ControlCenterHeaderHooker.supportsCompactLayout(binding.battery) &&
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_HIDE_DATE, false) &&
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_CARRIER_TWO_LINE, false)

    fun clearSignalMotions(binding: DuoBinding) {
        if (binding.dotsInMotion) {
            binding.dotsInMotion = false
            binding.view.icon.hideSignalDots = hideExpandedDots(binding)
            binding.view.invalidate()
        }
        binding.signalMotions.values.forEach { it.clear() }
        binding.signalMotions.clear()
        binding.signalTargets.values.forEach { ControlCenterCarrierBlockHooker.releaseDuoTarget(it, binding) }
        binding.signalTargets.clear()
        if (binding.view.icon.hiddenSignalRows.isNotEmpty()) {
            binding.view.icon.hiddenSignalRows = emptySet()
            binding.view.invalidate()
        }
    }

    private fun updateSignalMotions(binding: DuoBinding, home: DuoBinding, expanded: DuoBinding, root: ViewGroup?) {
        val content = home.view.icon.content
        val wifi = content?.wifiLevel != null
        val picture = if (wifi) home.view.icon.signalDotsPicture() else home.view.icon.cellularSignalPicture
        if (root == null || content == null || picture == null || content.airplaneMode) {
            clearSignalMotions(binding); return
        }
        val used = HashSet<Int>()
        val hidden = HashSet<Int>()
        val rows = if (wifi) content.activeDataSubId?.let { id ->
            listOf(DuoSignalRow(id, host.slotIndex(id) ?: -1, if (content.noService) -1 else content.mobileLevel))
        }.orEmpty() else content.cellularSignalRows
        var dotsReady = false
        rows.forEachIndexed { index, row ->
            val carrier = ControlCenterCarrierBlockHooker.signalHandoverTarget(expanded.icons, row.subId)
            val target = carrier ?: nativeSignalView(expanded, row.subId) ?: return@forEachIndexed
            val hasBounds = if (wifi) home.view.icon.signalDotsBounds(binding.signalPictureCrop, binding.signalSourceBounds)
                else home.view.icon.signalRowBounds(index, binding.signalPictureCrop, binding.signalSourceBounds)
            if (!hasBounds) return@forEachIndexed
            if (binding.signalTargets[row.subId] !== target) {
                binding.signalMotions.remove(row.subId)?.clear()
                binding.signalTargets.remove(row.subId)?.let { ControlCenterCarrierBlockHooker.releaseDuoTarget(it, binding) }
                if (carrier != null && !ControlCenterCarrierBlockHooker.acquireDuoTarget(carrier, binding)) return@forEachIndexed
                binding.signalTargets[row.subId] = target
            }
            val motion = binding.signalMotions.getOrPut(row.subId) { SignalRowMotion() }
            val destinationPicture = if (wifi) host.destinationSignal(binding.battery.context, row.level) else null
            if (motion.update(root, home.view, binding.signalSourceBounds, target, picture,
                    binding.signalPictureCrop, binding.view.icon.foreground, host.panelProgress, destinationPicture)) {
                if (wifi) dotsReady = true else hidden += index
            }
            used += row.subId
        }
        binding.signalMotions.keys.toList().filter { it !in used }.forEach { subId ->
            binding.signalMotions.remove(subId)?.clear()
            binding.signalTargets.remove(subId)?.let { ControlCenterCarrierBlockHooker.releaseDuoTarget(it, binding) }
        }
        if (binding.dotsInMotion != dotsReady) {
            binding.dotsInMotion = dotsReady
            binding.view.icon.hideSignalDots = dotsReady || hideExpandedDots(binding)
            binding.view.invalidate()
        }
        if (binding.view.icon.hiddenSignalRows != hidden) {
            binding.view.icon.hiddenSignalRows = hidden
            binding.view.invalidate()
        }
    }

    private fun nativeSignalView(binding: DuoBinding, subId: Int): View? {
        val root = binding.icons as? ViewGroup ?: return null
        val id = binding.networkIds["mobile_signal"]?.takeIf { it != 0 } ?: return null
        fun find(group: ViewGroup): View? {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (read(child, "subId") == subId) {
                    return child.findViewById<View>(id)?.takeIf { it.isVisible && it.width > 0 }
                }
                if (child is ViewGroup) find(child)?.let { return it }
            }
            return null
        }
        return find(root)
    }

    fun clearAirplaneMotion(binding: DuoBinding) {
        binding.airplaneMotion.clear()
        if (binding.view.icon.hideAirplane) {
            binding.view.icon.hideAirplane = false
            binding.view.invalidate()
        }
    }

    fun clearNetworkMotion(binding: DuoBinding) {
        binding.networkMotionSettle?.let(main::removeCallbacks)
        binding.networkMotionSettle = null
        binding.networkMotionProgress = 0f
        binding.panelMotion.clear()
        binding.carrierTarget?.let { ControlCenterCarrierBlockHooker.releaseDuoTarget(it, binding) }
        binding.carrierTarget = null
        if (binding.view.icon.hideNetwork) {
            binding.view.icon.hideNetwork = false
            binding.view.invalidate()
        }
    }

    fun clearPanelMotion(binding: DuoBinding) {
        clearNetworkMotion(binding)
        clearAirplaneMotion(binding)
    }

    fun hasActiveProxy(root: View): Boolean = host.enabled && host.bindings().any {
        it.active && it.surface == DuoSurface.COLLAPSED_PROXY &&
            ancestor(it.view, "com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons") === root
    }

    fun updatePanelBinding(binding: DuoBinding) {
        if (binding.surface != DuoSurface.COLLAPSED_PROXY) return
        binding.panelArrival.observe(host.panelProgress, host.panelVisible)
        fun clearPosition() { clearPanelMotion(binding); clearSignalMotions(binding); restoreProxyPosition(binding) }
        if (ControlCenterCarrierBlockHooker.isShadeSwitching()) {
            clearPosition()
            return
        }
        // The host just switched between full shade pages. A later expansion=1 callback must not
        // start the six-frame home-to-carrier settle from the cleared proxy's progress=0.
        if (host.shadeSwitchSettledToControlCenter && host.panelProgress >= 0.999f) return
        if (!binding.active || !host.panelVisible) { clearPosition(); return }
        val proxyRoot = ancestor(binding.view,
            "com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons")
            ?: run { clearPosition(); return }
        val home = host.bindings().firstOrNull { it.active && it.surface == DuoSurface.HOME &&
            it.view.isLaidOut && it.view.display?.displayId == binding.view.display?.displayId }
        val expanded = host.bindings().firstOrNull { it.surface == DuoSurface.EXPANDED &&
            it.parent.rootView === binding.parent.rootView && ancestor(it.battery,
                "com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon") != null }
        if (home == null || expanded == null || binding.view.width <= 0) { clearPosition(); return }
        val target = if (expanded.active) expanded.view else expanded.battery
        if (!target.isLaidOut || target.width <= 0) { clearPosition(); return }
        val expandedRoot = ancestor(target,
            "com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon")
            ?: run { clearPosition(); return }
        if (binding.panelArrival.settled) {
            // A temporary header endpoint (for example while brightness hides the backdrop)
            // must neither move the proxy home nor replay its Wi-Fi/signal overlays.
            positionProxy(binding, proxyRoot, home.view, target, expandedRoot, 1f)
            clearSignalMotions(binding)
            clearPanelMotion(binding)
            return
        }
        positionProxy(binding, proxyRoot, home.view, target, expandedRoot, host.panelProgress)
        updateSignalMotions(binding, home, expanded, proxyRoot.rootView as? ViewGroup)
        val content = binding.view.icon.content
        binding.view.icon.nativeWifi = home.view.icon.nativeWifi
        if (content == null) { clearPanelMotion(binding); return }
        val root = proxyRoot.rootView as? ViewGroup
        if (root != null) updateAirplaneMotion(binding, home.view, expanded, root, content, host.panelProgress)
        if (content.airplaneMode && content.wifiLevel == null) return
        val wifi = content.wifiLevel != null
        val destination = DuoPolicy.networkDestination(
            host.expandedStyle,
            ControlCenterCarrierBlockHooker.ownsNetwork(expanded.icons, wifi)
        )
        val networkTarget = when (destination) {
            DuoNetworkDestination.NONE -> null
            DuoNetworkDestination.CARRIER -> ControlCenterCarrierBlockHooker.duoHandoverTarget(
                expanded.icons, wifi, host.mobile.activeDataSubId)
            DuoNetworkDestination.NATIVE -> nativeNetworkView(expanded, wifi)
        }
        if (networkTarget == null || root == null) { clearNetworkMotion(binding); return }
        if (host.panelProgress >= 1f && binding.networkMotionProgress >= 1f) return
        val carrierTarget = networkTarget.takeIf { destination == DuoNetworkDestination.CARRIER }
        if (binding.carrierTarget !== carrierTarget) clearNetworkMotion(binding)
        if (carrierTarget != null) {
            if (!ControlCenterCarrierBlockHooker.acquireDuoTarget(carrierTarget, binding)) {
                clearNetworkMotion(binding); return
            }
            binding.carrierTarget = carrierTarget
        }
        if (host.panelProgress >= 1f) {
            settlePanelMotion(binding, root, home.view, networkTarget, content,
                home.view.icon.foreground, home.view.icon.nativeWifi as? NativeWifiDrawable)
            return
        }
        binding.networkMotionSettle?.let(main::removeCallbacks)
        binding.networkMotionSettle = null
        binding.networkMotionProgress = host.panelProgress
        val hidden = binding.panelMotion.update(root, home.view, networkTarget, content,
            home.view.icon.foreground, host.panelProgress, host.small5GaEnabled, host.componentSizes,
            home.view.icon.nativeWifi as? NativeWifiDrawable)
        if (binding.view.icon.hideNetwork != hidden) {
            binding.view.icon.hideNetwork = hidden
            binding.view.invalidate()
        }
    }

    private fun updateAirplaneMotion(binding: DuoBinding, source: View, expanded: DuoBinding,
                                     root: ViewGroup, content: DuoContent, progress: Float) {
        val icons = expanded.icons as? ViewGroup
        val target = icons?.let { group ->
            (0 until group.childCount).asSequence().map { group.getChildAt(it) }
                .firstOrNull { read(it, "mSlot") == AIRPLANE_SLOT && it.isLaidOut && it.width > 0 }
        }
        val hidden = if (content.airplaneMode && target != null) {
            val home = host.bindings().firstOrNull { it.view === source }
            val sourceColor = home?.view?.icon?.foreground ?: binding.view.icon.foreground
            binding.airplaneMotion.update(root, source, target, content,
                sourceColor, progress, host.small5GaEnabled, host.componentSizes)
        } else { binding.airplaneMotion.clear(); false }
        if (binding.view.icon.hideAirplane != hidden) {
            binding.view.icon.hideAirplane = hidden
            binding.view.invalidate()
        }
    }

    /** Finish one-step panel opens with the same short travel used while the user drags. */
    private fun settlePanelMotion(
        binding: DuoBinding,
        root: ViewGroup,
        source: View,
        target: View,
        content: DuoContent,
        color: Int,
        nativeWifi: NativeWifiDrawable?
    ) {
        if (binding.networkMotionSettle != null) return
        val from = binding.networkMotionProgress.coerceIn(0f, 1f)
        if (from >= 0.95f) {
            clearPanelMotion(binding)
            binding.networkMotionProgress = 1f
            binding.panelArrival.arrive()
            return
        }
        val runnable = DuoPanelSettle(from, object : DuoPanelSettle.Host {
            override fun current(task: Runnable) = binding.networkMotionSettle === task
            override fun live() = host.enabled && host.panelVisible && host.owns(binding)
            override fun progress(value: Float) { binding.networkMotionProgress = value }
            override fun draw(value: Float) {
                val expanded = host.bindings().firstOrNull { it.surface == DuoSurface.EXPANDED &&
                    it.parent.rootView === binding.parent.rootView }
                if (expanded != null) updateAirplaneMotion(binding, source, expanded, root, content, value)
                val hidden = binding.panelMotion.update(root, source, target, content, color,
                    value, host.small5GaEnabled, host.componentSizes, nativeWifi)
                if (binding.view.icon.hideNetwork != hidden) {
                    binding.view.icon.hideNetwork = hidden
                    binding.view.invalidate()
                }
            }
            override fun clear() = clearPanelMotion(binding)
            override fun arrive() = binding.panelArrival.arrive()
            override fun post(task: Runnable) { main.postDelayed(task, 24L) }
            override fun failed(error: Throwable) { DebugLog.w(TAG, "Duo panel hand-over settle failed", error) }
        })
        binding.networkMotionSettle = runnable
        main.postDelayed(runnable, 24L)
    }

    private fun nativeNetworkView(binding: DuoBinding, wifi: Boolean): View? {
        fun byId(root: View, name: String): View? = binding.networkIds[name]?.takeIf { it != 0 }
            ?.let { root.findViewById<View>(it) }?.takeIf { it.isVisible && it.width > 0 }
        if (wifi) return byId(binding.icons, "wifi_signal")
        val group = binding.icons as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.isVisible && read(child, "subId") == host.mobile.activeDataSubId)
                return byId(child, "mobile_type")
        }
        return null
    }

}
