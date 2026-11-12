@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import com.takekazex.hypertweak.hook.rules.systemui.icon.NetworkHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.WifiPipelineAdapter
import com.takekazex.hypertweak.hook.base.cachedDeclaredMethod
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.rules.systemui.icon.FrameUpdateGate
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeConfig
import com.takekazex.hypertweak.hook.rules.systemui.icon.ShadeSwitchMotionPolicy
import com.takekazex.hypertweak.hook.rules.systemui.icon.HandoverCoordinator
import com.takekazex.hypertweak.hook.rules.systemui.icon.TwoRowIconLayout
import com.takekazex.hypertweak.presentation.icon.MobileSignalState
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalHub

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
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerOptions
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility
import com.takekazex.hypertweak.settings.contract.IconSurface
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelHub
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import org.luckypray.dexkit.query.enums.StringMatchType
import com.takekazex.hypertweak.util.platform.ResourceLookup

internal fun CarrierRenderer.installBlock(layout: ViewGroup) {
        if (blocks.containsKey(layout)) return
        // A native view supplies its own environment even when PackageReady was missed.
        bindEnvironment(layout.context)
        knownLayouts[layout] = true
        if (layout.id == 0 || layout.id != carrierLayoutId) return
        val linear = layout as? LinearLayout ?: return
        val left = leftTextField?.get(layout) as? LinearLayout ?: return
        val right = rightTextField?.get(layout) as? LinearLayout ?: return
        val rows = listOfNotNull(presentation.buildRow(left, 0), presentation.buildRow(right, 1))
        if (rows.size != CarrierBlockPolicy.ROW_COUNT) return
        val block = CarrierBlock(linear, rows, separatorField?.get(layout) as? View,
            keyguardSeparatorField?.get(layout) as? View)
        blocks[layout] = block
        rows.forEach { rowIndex[it.row] = it; rowIndex[it.networkSlot] = it }
        try {
            rows.forEach { parts ->
                parts.row.addView(parts.signal, 0)
                parts.row.addView(parts.badge, 1)
                // Type and Wi-Fi share one measured row position; connecting cannot add a new box.
                parts.row.addView(parts.airplaneText)
                parts.row.addView(parts.networkSlot)
            }
            presentation.configureBlock(block)
            block.attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { observeBlock(block); scheduleRender() }
                override fun onViewDetachedFromWindow(v: View) {
                    stopObserving(block)
                    releaseMask(block)
                    handover.releaseHandover()
                    // Do not remove children during the host's detach traversal.
                    main.post {
                        if (!layout.isAttachedToWindow && blocks[layout] === block) {
                            runCatching { removeBlock(block) }
                                .onFailure { DebugLog.w(TAG, "carrier detach cleanup failed", it) }
                            blocks.remove(layout)
                        }
                    }
                }
            }.also(layout::addOnAttachStateChangeListener)
            if (layout.isAttachedToWindow) observeBlock(block)
            scheduleRender()
            DebugLog.i(TAG, "carrier block installed rows=${rows.size}")
        } catch (error: Throwable) {
            removeBlock(block)
            blocks.remove(layout)
            throw error
        }
    }

internal fun CarrierRenderer.removeBlock(block: CarrierBlock) {
        stopObserving(block)
        block.attachListener?.let(block.layout::removeOnAttachStateChangeListener)
        releaseMask(block)
        presentation.restoreBlockStyle(block)
        IconHostPorts.header.releaseCarrier(block.layout)
        block.rows.forEach { parts ->
            rowIndex.remove(parts.row)
            rowIndex.remove(parts.networkSlot)
            handover.retireTarget(parts.signal)
            handover.retireTarget(parts.wifi)
            handover.retireTarget(parts.type)
            parts.networkFade.clear()
            listOf(parts.signal, parts.badge, parts.airplaneText, parts.networkSlot).forEach(parts.row::removeView)
        }
    }

internal fun CarrierRenderer.observeBlock(block: CarrierBlock) {
        stopObserving(block)
        block.observer = block.layout.viewTreeObserver
        block.preDraw = ViewTreeObserver.OnPreDrawListener {
            runCatching {
                if (block.compact) {
                    syncNativeWifi(block)
                    if (syncNativeSignals(block) { it.model }) scheduleRender()
                    block.rows.forEach(rowRenderer::syncTint)
                }
                presentation.updateRowGeometry(block)
                IconHostPorts.header.updateCarrierLayout(block.layout, true)
                syncMask(block)
                if (!shadeSwitching && panelVisible && progress > 0f && progress < 1f) {
                    handover.applyHandoverGuarded(progress)
                }
            }.onFailure { DebugLog.w(TAG, "carrier presentation update failed", it) }
            true
        }.also { block.observer?.addOnPreDrawListener(it) }
    }

internal fun CarrierRenderer.stopObserving(block: CarrierBlock) {
        block.preDraw?.let { listener ->
            block.observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        block.preDraw = null
        block.observer = null
    }
