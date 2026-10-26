package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.presentation.icon.duo.DuoSurface

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.rules.aod.AodIconSettings
import com.takekazex.hypertweak.hook.rules.systemui.AODHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconPositionHooker
import com.takekazex.hypertweak.settings.contract.AodSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Owns AOD phase discovery and native keyguard-row restoration. */
internal class DuoAodController(owner: BaseHooker, private val host: Host) : HookComponent(owner) {
    interface Host {
        val enabled: Boolean
        val standardEnabled: Boolean
        val epoch: Int
        val batteryLayoutField: Field?
        val visibilityMethod: Method?
        fun refresh()
        fun restore(binding: DuoBinding)
        fun setPrivacyInset(binding: DuoBinding, inset: Int)
        fun setNativeAirplaneMasked(binding: DuoBinding, masked: Boolean)
    }
    private companion object { const val TAG = "DuoSignal" }
    private val main = Handler(Looper.getMainLooper())
    private var dozingRead: (() -> Boolean?)? = null
    fun retire() { dozingRead = null }
    private fun read(target: Any, name: String) = HostAccess.read(target, name)
    private fun field(type: Class<*>, name: String) = HostAccess.field(type, name)
    private inline fun guarded(action: () -> Unit) {
        runCatching(action).onFailure { DebugLog.w(TAG, "host callback failed", it) }
    }
    fun resolveDozingSource() {
        if (dozingRead != null) return
        val managerClass = FullAodDozingSource.MANAGER.toClassOrNull() ?: run {
            DebugLog.w(TAG, "full-AOD manager missing; AOD options stay off the lock-screen row")
            return
        }
        runCatching {
            val source = FullAodDozingSource(managerClass,
                requireNotNull(FullAodDozingSource.REGISTRY.toClassOrNull()),
                requireNotNull(FullAodDozingSource.API.toClassOrNull()))
            // Also cover cold startup before the registry has published its lazy manager.
            val token = host.epoch
            managerClass.hookAllConstructors {
                after { param ->
                    source.bind(param.thisObject)
                    main.post { if (host.enabled && token == host.epoch) guarded { host.refresh() } }
                }
            }
            dozingRead = { runCatching(source::read).getOrNull() }
            DebugLog.d(TAG, if (source.read() == null) "dozing source waiting for native manager" else "dozing source resolved")
        }.onFailure {
            DebugLog.w(TAG, "full-AOD dozing contract unavailable; preserving native row", it)
        }
    }

    /** Current keyguard phase, from the dozing flag and the attached keyguard host. */
    fun phaseFor(keyguardRoot: View?): AodHostPhase {
        val root = keyguardRoot ?: return AodHostPhase.UNKNOWN
        if (!root.isAttachedToWindow) return AodHostPhase.UNKNOWN
        return AodKeyguardPolicy.phaseFromDozing(dozingRead?.invoke(), true)
    }

    /** Keyguard-row decision for one pass; every non-keyguard surface is unconditionally Duo. */
    fun keyguardDecision(binding: DuoBinding): KeyguardRowDecision {
        val root = binding.keyguardRoot
            ?: return KeyguardRowDecision.of(AodHostPhase.UNKNOWN, KeyguardRowOwner.DUO)
        val phase = AodKeyguardPolicy.phaseForFrame(
            host.enabled, root.isAttachedToWindow, binding.rowPhase
        ) { dozingRead?.invoke() }
        val settings = if (phase == AodHostPhase.LIT || phase == AodHostPhase.UNKNOWN)
            AodIconSettings.defaults() else AodIconSettings.current()
        return AodKeyguardPolicy.decide(
            phase = phase,
            standardDuo = host.standardEnabled,
            aodCustomized = settings.customized,
            aodDuo = settings.duo,
            // Capability, never the current mode: it selects the AOD renderer only inside an
            // AOD phase, where the dozing flag has already proven the AOD surface is showing.
            fullAodRendering = AODHooker.isFullscreenSupported()
        )
    }

    /** A full-AOD handshake may change the keyguard state outside a reconciliation. */
    fun observeHandshake(binding: DuoBinding, toLockScreen: Boolean) {
        if (binding.surface != DuoSurface.KEYGUARD) return
        val root = binding.keyguardRoot ?: return
        val phase = AodKeyguardPolicy.phaseAfterHandshake(
            toLockScreen, root.isAttachedToWindow, dozingRead?.invoke())
        if (binding.rowPhase == phase) return
        val previous = binding.rowPhase
        binding.rowPhase = phase
        DebugLog.d(TAG, "keyguard handshake toLockScreen=" + toLockScreen +
            " phase=" + previous.name + "->" + phase.name)
    }

    /**
     * Hides the native row for an AOD frame that cannot render. Only an AOD phase owns that: a
     * lit lock screen keeps whatever the host and the regular Duo columns decided.
     */
    fun hideForAod(binding: DuoBinding) {
        if (keyguardDecision(binding).keepsNativeRow) {
            host.restore(binding)
            return
        }
        binding.view.visibility = View.INVISIBLE
        binding.battery.visibility = View.GONE
        binding.aodNativeSuppressed = true
    }

    /** Releases every keyguard-row change this binding owns and returns the native row. */
    fun releaseKeyguardRow(binding: DuoBinding, phase: AodHostPhase) {
        restoreNativeFullAod(binding)
        host.restore(binding)
        binding.rowPhase = phase
    }
    /** Keep the keyguard icon lease through AOD so the host's fade never exposes stock icons. */
    fun holdHiddenAod(binding: DuoBinding) {
        binding.view.visibility = View.INVISIBLE
        binding.battery.visibility = View.GONE
        binding.aodNativeSuppressed = true
        // Restoring the lease here exposes the original stacked/mobile row while the host's
        // mStatusIconContainer alpha is still above zero.
        IconPositionHooker.setDuoMask(binding.icons, true)
        host.setNativeAirplaneMasked(binding, true)
    }

    fun showNativeFullAod(binding: DuoBinding, settings: AodIconSettings) {
        val battery = binding.battery as? ViewGroup ?: return
        val icon = read(battery, "mBatteryDigitalView") as? View ?: return
        val percent = read(battery, "mBatteryPercentContainer") as? View ?: return
        if (binding.fullAodNativeState == null) {
            val charging = read(battery, "mBatteryChargingView") as? View
            binding.fullAodNativeState = FullAodNativeState(icon, percent,
                charging, icon.visibility, percent.visibility, charging?.visibility)
        }
        if (binding.active) {
            if (host.batteryLayoutField?.get(binding.parent) === binding.view) {
                host.batteryLayoutField?.set(binding.parent, battery)
            }
            binding.active = false
            binding.parent.requestLayout()
        }
        binding.view.visibility = View.INVISIBLE
        host.setPrivacyInset(binding, 0)
        field(binding.parent.javaClass, "mIsHideBattery")?.setBoolean(binding.parent, false)
        IconPositionHooker.setDuoMask(binding.icons, true)
        host.setNativeAirplaneMasked(binding, true)
        applyFullAodNativeChildren(binding)
        val rowVisibility = if (settings.showRow) View.VISIBLE else View.GONE
        if (battery.visibility != rowVisibility) battery.visibility = rowVisibility
        positionNativeFullAod(binding)
        binding.aodNativeSuppressed = true
    }

    fun applyFullAodNativeChildren(binding: DuoBinding) {
        val state = binding.fullAodNativeState ?: return
        if (binding.surface != DuoSurface.KEYGUARD || binding.rowPhase == AodHostPhase.LIT) return
        if (binding.keyguardRoot?.let { read(it, "mToLockScreen") } != false) return
        val settings = AodIconSettings.current()
        if (settings.duo) return
        val iconVisibility = if (settings.batteryIcon) View.VISIBLE else View.GONE
        val percentVisibility = if (settings.percent) View.VISIBLE else View.GONE
        if (state.iconContainer.visibility != iconVisibility)
            state.iconContainer.visibility = iconVisibility
        if (state.percentContainer.visibility != percentVisibility)
            state.percentContainer.visibility = percentVisibility
        if (!settings.batteryIcon) {
            state.chargingView?.visibility = View.GONE
        }
    }

    fun positionNativeFullAod(binding: DuoBinding) {
        val state = binding.fullAodNativeState ?: return
        val battery = binding.battery
        val icon = state.iconContainer.takeIf { it.isVisible }
        val percent = state.percentContainer.takeIf { it.isVisible }
        if (icon == null || percent == null || battery.width <= 0) return
        val charging = (read(battery, "mBatteryChargingView") as? View)
            ?.takeIf { it.isVisible }
        val left = battery.paddingLeft
        val percentLeft = if (AodIconSettings.current().percentPosition ==
            AodSettingsKeys.AOD_PERCENT_POSITION_LEFT) left else
            left + icon.measuredWidth + (charging?.measuredWidth ?: 0)
        val iconLeft = if (percentLeft == left) left + percent.measuredWidth else left
        icon.layout(iconLeft, icon.top, iconLeft + icon.measuredWidth, icon.bottom)
        charging?.layout(iconLeft + icon.measuredWidth, charging.top,
            iconLeft + icon.measuredWidth + charging.measuredWidth, charging.bottom)
        percent.layout(percentLeft, percent.top,
            percentLeft + percent.measuredWidth, percent.bottom)
    }

    fun restoreNativeFullAod(binding: DuoBinding) {
        val state = binding.fullAodNativeState ?: return
        binding.fullAodNativeState = null
        val battery = binding.battery as? ViewGroup ?: return
        state.iconContainer.visibility = state.iconVisibility
        state.percentContainer.visibility = state.percentVisibility
        state.chargingVisibility?.let { state.chargingView?.visibility = it }
        host.visibilityMethod?.invoke(battery)
        binding.aodNativeSuppressed = false
    }

}
