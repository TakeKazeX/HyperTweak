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

internal fun CarrierRenderer.bindEnvironment(context: Context) {
        val app = context.applicationContext ?: context
        hostContext = app
        resolveIds(context)
        if (enabled) bindSimNames(app)
    }

    /** Subscription display names include Settings SIM renames; carrier/SPN text does not. */
    // Runs with SystemUI's manifest-granted READ_PHONE_STATE, not the module app identity.
    @SuppressLint("MissingPermission")
internal fun CarrierRenderer.bindSimNames(context: Context) {
        if (!enabled || subscriptionListener != null) return
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val token = generation.get()
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                if (!enabled || generation.get() != token) return
                runCatching {
                    simNames = manager.activeSubscriptionInfoList.orEmpty().mapNotNull { info ->
                        info.displayName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                            ?.let { info.subscriptionId to it }
                    }.toMap()
                    scheduleRender()
                }.onFailure {
                    simNames = emptyMap()
                    scheduleRender()
                    DebugLog.w(TAG, "SIM name refresh failed; retaining native names", it)
                }
            }
        }
        runCatching {
            manager.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
            subscriptionManager = manager
            subscriptionListener = listener
            listener.onSubscriptionsChanged()
        }.onFailure { DebugLog.w(TAG, "SIM name listener unavailable", it) }
    }

internal fun CarrierRenderer.recoverExistingViews(context: Context, views: List<View>, savedProgress: Float?, visible: Boolean?) {
        if (!enabled) return
        check(Looper.myLooper() == Looper.getMainLooper())
        // Context/resources must be ready BEFORE accepting a saved or discovered host.
        bindEnvironment(context)
        views.filter { it.javaClass.name == LAYOUT_CLASS }.filterIsInstance<ViewGroup>()
            .forEach { knownLayouts[it] = true }
        recoveryTask?.let(main::removeCallbacks)
        recoveryAttempt = 0
        lastRecoveryEvidence = null
        val token = generation.get()
        val task = Runnable {
            runCatching { recoverHosts(token) }.onFailure { error ->
                DebugLog.w(TAG, "carrier recovery attempt=$recoveryAttempt failed", error)
                if (enabled && generation.get() == token && recoveryAttempt < CarrierRecoveryPolicy.MAX_ATTEMPTS) {
                    recoveryTask?.let { main.postDelayed(it, CarrierRecoveryPolicy.RETRY_DELAY_MS) }
                } else recoveryTask = null
            }
        }
        recoveryTask = task
        task.run()
    }

    /** Reconcile saved, attached-window and controller-owned (possibly detached) hosts. */
internal fun CarrierRenderer.recoverHosts(token: Long) {
        if (!enabled || generation.get() != token) return
        val context = hostContext ?: return
        recoveryAttempt++
        bindEnvironment(context)
        val app = context.applicationContext ?: context
        val component = HostAccess.read(app, "mSysUIComponent")
            ?: HostAccess.read(app, "mInitializer")?.let { HostAccess.invoke(it, "getSysUIComponent") }
        if (component != null) {
            runCatching {
                val header = HostAccess.provider(component, "combinedHeaderControllerProvider")
                val layout = header?.let { HostAccess.read(it, "controlCenterCarrierLayout") } as? ViewGroup
                if (layout != null) knownLayouts[layout] = true
            }.onFailure { DebugLog.w(TAG, "carrier host/source discovery failed", it) }
        }
        fun visit(view: View) {
            if (view.javaClass.name == LAYOUT_CLASS && view is ViewGroup) knownLayouts[view] = true
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        knownLayouts.keys.toList().forEach { layout ->
            runCatching { installBlock(layout) }.onFailure { DebugLog.w(TAG, "carrier recovered host rejected", it) }
        }
        scheduleRender()
        handover.applyHandoverGuarded(progress)
        val evidence = CarrierRecoveryEvidence(
            environmentReady = carrierLayoutId != 0,
            hosts = blocks.size,
            rows = blocks.values.sumOf { it.rows.size },
            artworkReady = mobileState.rows.none { it.supportsReplacement && it.signalIconId == null },
            wifiBound = NetworkHub.wifiBound,
            mobileBound = IconHostPorts.mobileReady()
        )
        if (evidence != lastRecoveryEvidence) {
            lastRecoveryEvidence = evidence
            DebugLog.i(TAG, "carrier recovery attempt=$recoveryAttempt hosts=${evidence.hosts} " +
                "attached=${blocks.keys.filterIsInstance<View>().count { it.isAttachedToWindow }} " +
                "rows=${evidence.rows} missing=${evidence.missing}")
        }
        when (CarrierRecoveryPolicy.decide(enabled && generation.get() == token, recoveryAttempt, evidence)) {
            CarrierRecoveryPolicy.Decision.PREPARED -> {
                recoveryTask = null
                DebugLog.i(TAG, "carrier recovery prepared hosts=${evidence.hosts} rows=${evidence.rows}")
            }
            CarrierRecoveryPolicy.Decision.RETRY -> recoveryTask?.let { main.postDelayed(it, CarrierRecoveryPolicy.RETRY_DELAY_MS) }
            CarrierRecoveryPolicy.Decision.EXHAUSTED -> {
                recoveryTask = null
                DebugLog.w(TAG, "carrier recovery incomplete after $recoveryAttempt attempts missing=${evidence.missing}")
            }
            CarrierRecoveryPolicy.Decision.RETIRED -> Unit
        }
    }
