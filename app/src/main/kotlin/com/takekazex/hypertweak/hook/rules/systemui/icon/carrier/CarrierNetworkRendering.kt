@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.cachedDeclaredMethod
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.rules.systemui.icon.FrameUpdateGate
import com.takekazex.hypertweak.hook.rules.systemui.icon.HandoverCoordinator
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerOptions
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeConfig
import com.takekazex.hypertweak.hook.rules.systemui.icon.NetworkHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.ShadeSwitchMotionPolicy
import com.takekazex.hypertweak.hook.rules.systemui.icon.TwoRowIconLayout
import com.takekazex.hypertweak.hook.rules.systemui.icon.WifiPipelineAdapter
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.presentation.icon.MobileSignalState
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSurface
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.ResourceLookup
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import org.luckypray.dexkit.query.enums.StringMatchType

internal fun CarrierRenderer.hiddenSimIds(): Set<Int> = networkVisibility.hiddenCellularIds(
        mobileState, IconSurface.CONTROL_CENTER, wifiDefault ?: mobileState.wifiConnected
    ) { slotIndices[it] ?: CarrierBlockPolicy.INVALID_SLOT }

internal fun CarrierRenderer.hiddenCarrierSlots(): Set<Int> = (0 until CarrierBlockPolicy.ROW_COUNT)
    .filterTo(linkedSetOf()) { slot -> networkVisibility.hidesCarrier(slot, mobileState, ::slotOf) }

internal fun CarrierRenderer.hidesWifi(): Boolean =
        networkVisibility.hidesWifi(IconSurface.CONTROL_CENTER, wifiDefault == true || wifiLevel != null)

internal fun CarrierRenderer.renderAll() {
        if (!enabled || blocks.isEmpty()) return
        slotIndices = mobileState.subscriptionOrder.associateWith(::slotOf)
        val rows = CarrierBlockPolicy.resolve(
            state = mobileState,
            wifiLevel = wifiLevel.takeIf { wifiDefault == true && !hidesWifi() },
            config = CarrierBlockConfig(
                showNonDataType = showNonDataType,
                keepTypeOnWifi = keepTypeOnWifi,
                dataFirst = dataFirst,
                networkOnFirstRow = networkOnFirstRow,
                hiddenSimIds = hiddenSimIds(),
                hiddenCarrierSlots = hiddenCarrierSlots(),
                hideTypeWhenDisconnected = hideTypeWhenDisconnected
            ),
            wifiDefault = wifiDefault ?: mobileState.wifiConnected,
            slotOf = { slotIndices[it] ?: CarrierBlockPolicy.INVALID_SLOT }
        )
        rowOrder = rows.map { it.slot }
        blocks.values.toList().forEach { block ->
            if (!block.layout.isAttachedToWindow) return@forEach
            if (block.compact) {
                val rendering = CarrierRenderInput(
                    mobileState, simNames, showBadge, keepTypeOnWifi, typeConfig, block.nativeWifi, hiddenCarrierSlots()
                )
                syncNativeSignals(block) { parts -> rows.firstOrNull { it.slot == parts.slot } }
                block.rows.forEach { parts -> rowRenderer.renderRow(parts, rows, rendering) }
                presentation.updateRowGeometry(block)
                IconHostPorts.header.updateCarrierLayout(block.layout, true)
            } else releaseMask(block)
        }
    }

    // ---------------------------------------------------------------- hand-over

    // ---------------------------------------------------------------- status row mask

internal fun CarrierRenderer.syncNativeSignals(block: CarrierBlock, model: (CarrierRowParts) -> CarrierRowModel?): Boolean {
        val header = block.layout.parent as? ViewGroup ?: return false
        val fake = header.findViewById<ViewGroup>(fakeStatusBarId)
        val real = header.findViewById<ViewGroup>(statusBarId)
        val stacked = stackedSignal
        val duo = duoSignal
        var changed = false
        for (parts in block.rows) {
            val row = model(parts)?.takeIf { it.hasSignal }
            val bars = row?.subId?.let { subId ->
                fake?.let { handover.nativeSignal(it, subId) }
                    ?: real?.let { handover.nativeSignal(it, subId) }
            }
            val previous = parts.nativeSignal
            val width = previous?.intrinsicWidth
            val height = previous?.intrinsicHeight
            val next = bars?.let {
                val badge = if (row.allowsNativeBadge(stacked, duo)) handover.inlineBadge(bars) else null
                val source = badge?.parent as? ViewGroup ?: bars
                val children = badge?.let { listOf(bars, it) }
                val glyph = previous?.takeIf { it.source === source && it.children == children }
                    ?: CarrierNativeGlyph(source, children)
                glyph.takeIf { it.update() }
            }
            if (previous !== next || width != next?.intrinsicWidth || height != next?.intrinsicHeight) changed = true
            parts.nativeSignal = next
        }
        return changed
    }

internal fun CarrierRenderer.syncNativeWifi(block: CarrierBlock) {
        val header = block.layout.parent as? ViewGroup
        val row = header?.findViewById<View>(statusBarId)
        val source = row?.let { WifiPipelineAdapter.nativeGlyph(it, wifiSignalId) } as? ViewGroup
        val previous = block.nativeWifi
        val previousWidth = previous?.intrinsicWidth
        val previousHeight = previous?.intrinsicHeight
        val next = if (source != null && wifiDefault == true && wifiLevel != null && !hidesWifi()) {
            val existing = previous?.takeIf { it.source === source }
            val ready = existing ?: CarrierNativeGlyph(source).takeIf {
                source.visibility == View.VISIBLE &&
                    source.findViewById<ImageView>(wifiSignalId)?.drawable != null
            }
            ready?.takeIf { it.update() }
        } else null
        block.nativeWifi = next
        if (previous !== next || previousWidth != next?.intrinsicWidth || previousHeight != next?.intrinsicHeight) {
            scheduleRender()
        }
    }

internal fun CarrierRenderer.syncMask(block: CarrierBlock) {
        if (!block.compact || !block.layout.isAttachedToWindow || !block.layout.isShown) {
            releaseMask(block)
            return
        }
        val header = block.layout.parent as? ViewGroup ?: return
        val row = header.findViewById<View>(statusBarId) as? ViewGroup ?: return
        val container = findStatusContainer(row) ?: return
        if (block.maskContainer !== container) releaseMask(block)
        val hidden = hiddenSimIds()
        val allowed = mobileState.subscriptionOrder.filterNot { it in hidden }
        val allowedRows = block.rows.filter { it.model?.subId in allowed }
        val cellular = CarrierBlockPolicy.replacesStatusSignal(allowedRows.mapNotNull { it.model }, allowed) &&
            allowedRows.all { it.row.isLaidOut && it.signal.isVisible && it.signal.drawable != null }
        val wifiReplacement = block.rows.any {
            it.wifi.isVisible && it.wifi.width > 0 && it.nativeWifi != null && it.nativeWifi === block.nativeWifi
        }
        val hideCellular = hidden.isNotEmpty() && allowed.isEmpty()
        val hideWifi = hidesWifi()
        val mask = CarrierMask(
            cellular = hideCellular || cellular,
            wifi = hideWifi || CarrierBlockPolicy.masksWifi(wifiReplacement, wifiDefault, cellular)
        )
        val acquired = IconHostPorts.positions.carrierMask(container, mask)
        if (acquired) {
            block.maskContainer = container
            if (mask.active) maskedContainers[container] = mask else maskedContainers.remove(container)
        }
        block.rows.forEach { parts ->
            parts.cellularReady = acquired && cellular && parts.model?.subId in allowed
            parts.wifiReady = acquired && !hideWifi && wifiReplacement && parts.nativeWifi != null && parts.nativeWifi === block.nativeWifi
            val signalReady = parts.cellularReady
            if (!signalReady || !handover.ownsTarget(parts.signal))
                parts.signal.alpha = if (signalReady) 1f else 0f
            parts.networkSlot.alpha = if (parts.cellularReady || parts.wifiReady) 1f else 0f
        }
    }

internal fun CarrierRenderer.releaseMask(block: CarrierBlock) {
        block.rows.forEach { parts ->
            parts.cellularReady = false; parts.wifiReady = false
            parts.signal.alpha = 0f; parts.networkSlot.alpha = 0f
        }
        val container = block.maskContainer ?: return
        IconHostPorts.positions.carrierMask(container, CarrierMask())
        maskedContainers.remove(container)
        block.maskContainer = null
    }

internal fun CarrierRenderer.findStatusContainer(root: ViewGroup): Any? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 512) {
            val view = queue.removeFirst()
            if (view.javaClass.name == STATUS_ICON_CONTAINER_CLASS) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
            }
        }
        return null
    }
