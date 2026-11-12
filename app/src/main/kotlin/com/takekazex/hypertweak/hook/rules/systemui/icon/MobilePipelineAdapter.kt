package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.telephony.SubscriptionManager
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.concurrent.atomic.AtomicLong

/** Owns host mobile subscriptions independently of the stacked renderer. */
object MobilePipelineAdapter : StaticHooker() {
    private val generation = AtomicLong()
    private var enabled by generationState { false }
    private var context: Context? by generationState { null }
    private val separateType by hookSetting(false) { Preferences.separateCellularType() }
    private val keepOnWifi by hookSetting(false) { Preferences.cellularTypeKeepsOnWifi() }
    private val hideDisconnected by hookSetting(false) { Preferences.cellularTypeHidesWhenDisconnected() }
    private val visibility by hookSetting(NetworkIconVisibility()) {
        IconTunerOptions.networkVisibility()
    }
    private val nativeWifiType = NativeWifiTypeVisibility(this)
    private var nativeTypeInstalled by generationState { false }
    private val source = MobileSignalSource(this, generation, { enabled }, { context },
        onAdapterReady = { controller, token ->
            HostAccess.onMain {
                if (enabled && generation.get() == token) {
                    context = context ?: (runCatching { HostAccess.read(controller, "mContext") }.getOrNull() as? Context)
                    MobileSignalHub.publishController(controller)
                }
            }
        }, scheduleRender = { token ->
            HostAccess.onMain { if (enabled && generation.get() == token) publish() }
        })

    init {
        hostRecovery(HostRecoveryPhase.ADAPTER, icons = true) { it.adapter?.let(source::recoverAdapter) }
    }

    override fun onHook() {
        enabled = true
        generation.incrementAndGet()
        IconTunerFlows.init(classLoader)
        nativeTypeInstalled = nativeWifiType.install()
        if (!MobileSignalVisibility.installGetter(this)) {
            enabled = false
            DebugLog.hookSkipped("IconTuner", "mobile pipeline", "visibility getter unavailable")
            return
        }
        source.hookCreateViewModel()
        source.hookAdapterStart()
        source.restore(source.snapshot())
        source.scheduleExistingAdapterDiscovery()
    }

    fun onPackageReady(value: Context) {
        context = value
        if (enabled) source.scheduleExistingAdapterDiscovery()
    }

    internal val mobilePipelineConnected get() = source.mobilePipelineConnected
    internal fun recoverAdapter(adapter: Any) = source.recoverAdapter(adapter)
    override fun saveHotReloadState(): Any? = source.snapshot()
    override fun restoreHotReloadState(state: Any?) = source.restore(state)

    fun refreshSettings() { HostAccess.onMain { if (enabled) publish() } }

    private fun publish() {
        val state = source.signalState
        val complete = source.complete
        if (nativeTypeInstalled) {
            val wifi = state.wifiConnected
            val allowType = separateType && if (wifi) keepOnWifi && !visibility.hideMobileOnWifi else !hideDisconnected
            val id = state.wifiDataSubscription(wifiDefault = true)?.subId?.takeIf {
                allowType && complete && it !in visibility.hiddenSimIds(state, wifi) { subId ->
                    runCatching { SubscriptionManager.getSlotIndex(subId) }.getOrDefault(-1)
                }
            }
            nativeWifiType.publish(id)
        }
        MobileSignalHub.publish(state, complete)
    }

    override fun onPrepareHotReload() {
        enabled = false
        generation.incrementAndGet()
        HostAccess.onMain {
            nativeWifiType.retire()
            MobileSignalHub.publish(source.signalState, false)
            source.retire()
            MobileSignalVisibility.clearForHotReload()
            MobileSignalHub.publishController(null)
        }
    }
}
