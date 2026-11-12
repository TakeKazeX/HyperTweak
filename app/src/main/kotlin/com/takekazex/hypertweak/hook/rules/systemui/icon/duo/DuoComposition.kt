@file:Suppress("StaticFieldLeak")
package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.orSkip
import com.takekazex.hypertweak.hook.rules.systemui.aod.AodIconSettings
import com.takekazex.hypertweak.hook.rules.systemui.icon.HandoverCoordinator
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerOptions
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeHostMetrics
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeTypographySettings
import com.takekazex.hypertweak.hook.rules.systemui.icon.NetworkHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.SlotMaskApplier
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.presentation.icon.MobileSignalState
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility
import com.takekazex.hypertweak.presentation.icon.duo.DuoBattery
import com.takekazex.hypertweak.presentation.icon.duo.DuoContent
import com.takekazex.hypertweak.presentation.icon.duo.DuoExpandedStyle
import com.takekazex.hypertweak.presentation.icon.duo.DuoLayout
import com.takekazex.hypertweak.presentation.icon.duo.DuoPolicy
import com.takekazex.hypertweak.presentation.icon.duo.DuoSizes
import com.takekazex.hypertweak.presentation.icon.duo.DuoSurface
import com.takekazex.hypertweak.presentation.icon.duo.NativeWifiDrawable
import com.takekazex.hypertweak.presentation.icon.toDuoNetwork
import com.takekazex.hypertweak.settings.contract.AodSettingsKeys
import com.takekazex.hypertweak.settings.contract.CcBatteryPercentStyle
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSurface
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import com.takekazex.hypertweak.util.platform.ResourceLookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import kotlin.math.roundToInt

internal fun DuoRenderer.attach(battery: View) {
        if (bindings.containsKey(battery)) return
        val parent = battery.parent as? ViewGroup ?: return
        if (parent.javaClass.name != CONTAINER || batteryLayoutField?.get(parent) !== battery) return
        val surface = surface(battery)
        // Keep dormant expanded bindings too: switching from native to Duo must not require
        // that the already-attached control-center header happens to be reinflated.
        if (surface == DuoSurface.UNSUPPORTED) return
        if (!standardEnabled && surface != DuoSurface.KEYGUARD) return
        val icons = statusIconsField?.get(parent) as? View ?: return
        val binding = DuoBinding(battery, parent, icons, surface,
            if (surface == DuoSurface.KEYGUARD)
                ancestor(battery, "com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView") else null,
            panelVisible && panelProgress >= 0.999f)
        binding.hostHideBattery = read(parent, "mIsHideBattery") as? Boolean ?: false
        binding.rowPhase = aodController.phaseFor(binding.keyguardRoot)
        binding.view.setIconSize(iconSizeDp)
        binding.view.setPaddingRelative((4f * battery.resources.displayMetrics.density).roundToInt(), 0, 0, 0)
        captureAirplaneMaskBaseline(binding)
        bindings[battery] = binding
        binding.view.drawFailed = { _ ->
            binding.failed = true
            main.post { guarded { reconcile(binding) } }
        }
        // Use the parent's own LayoutParams class, required by measureChildWithMargins.
        val original = battery.layoutParams
        // OS4 LayoutParams has no declared constructor (DEX constructor elision). The parent
        // only casts to MarginLayoutParams, so use the platform copy constructor.
        binding.view.layoutParams = (if (original is ViewGroup.MarginLayoutParams)
            ViewGroup.MarginLayoutParams(original) else ViewGroup.MarginLayoutParams(original)).apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.view.visibility = View.GONE
        binding.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        binding.view.setOnClickListener { _ ->
            if (enabled && standardEnabled && binding.active && settingDuoClickExpand &&
                binding.surface == DuoSurface.HOME && !panelVisible) {
                binding.controlCenterClick?.expand()
            }
        }
        binding.view.isClickable = false
        try { parent.addView(binding.view, parent.indexOfChild(battery) + 1) } catch (error: Throwable) {
            bindings.remove(battery)
            throw error
        }
        binding.observer = parent.viewTreeObserver
        binding.preDraw = ViewTreeObserver.OnPreDrawListener {
            guarded { reconcile(binding); panelHandover.updatePanelBinding(binding) }
            // This listener observes one icon. It must never veto the whole shade/window frame,
            // including while the host expansion animation keeps requesting layout.
            true
        }.also { binding.observer?.addOnPreDrawListener(it) }
        reconcile(binding)
    }

internal fun DuoRenderer.surface(view: View): DuoSurface {
        var node = view.parent
        repeat(20) {
            val current = node as? View ?: return DuoSurface.UNSUPPORTED
            when (current.javaClass.name) {
                "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView" -> return DuoSurface.HOME
                "com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView" -> return DuoSurface.KEYGUARD
                "com.android.systemui.qs.MiuiQSHeaderView",
                "com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon" -> return DuoSurface.EXPANDED
                "com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons" -> return DuoSurface.COLLAPSED_PROXY
            }
            node = current.parent
        }
        return DuoSurface.UNSUPPORTED
    }

    /** Use current parent geometry, never the GONE battery's stale portrait coordinates. */
internal fun DuoRenderer.position(binding: DuoBinding) {
        val parent = binding.parent
        val view = binding.view
        if (parent.width <= 0 || parent.height <= 0) return
        val box = DuoLayout.box(parent.width, parent.height, parent.paddingLeft, parent.paddingTop,
            parent.paddingRight, parent.paddingBottom,
            view.desiredIconSidePx(),
            view.paddingStart + view.paddingEnd + view.leadingPercentSlotWidthPx(),
            parent.layoutDirection == View.LAYOUT_DIRECTION_RTL,
            preserveSourceHeight = view.sourceIconSidePx != null)
        view.measure(View.MeasureSpec.makeMeasureSpec(box.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(box.height, View.MeasureSpec.EXACTLY))
        if (view.left != box.left || view.top != box.top || view.width != box.width || view.height != box.height)
            view.layout(box.left, box.top, box.left + box.width, box.top + box.height)
    }

internal fun DuoRenderer.reconcile(binding: DuoBinding) {
        if (binding.reconciling) return
        binding.reconciling = true
        try {
            expandedStyle = if (settingIconDuoExpanded == 0)
                DuoExpandedStyle.KEEP_DUO else DuoExpandedStyle.RESTORE_NATIVE
            binding.view.setClickTarget(if (standardEnabled && settingDuoClickExpand &&
                binding.surface == DuoSurface.HOME && binding.controlCenterClick != null)
                ancestor(binding.battery, "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView") else null)
            binding.view.sourceIconSidePx = if (binding.surface == DuoSurface.COLLAPSED_PROXY) {
                bindings.values.firstOrNull {
                    it.active && it.surface == DuoSurface.HOME && it.view.isLaidOut &&
                        it.view.display?.displayId == binding.view.display?.displayId
                }?.view?.drawnIconSidePx()
            } else null
            val battery = binding.battery
            if (binding.active && batteryLayoutField?.get(binding.parent) !== binding.view) binding.failed = true
            if (!enabled && binding.keyguardRoot != null) {
                binding.batteryAnimationToAod = null
                aodController.releaseKeyguardRow(binding, AodHostPhase.UNKNOWN)
                return
            }
            val decision = aodController.keyguardDecision(binding)
            // Nothing owns this keyguard row: release every borrowed reference, mask and native
            // child so a lit lock screen keeps exactly the host's result.
            if (decision.owner == KeyguardRowOwner.NONE) {
                aodController.releaseKeyguardRow(binding, decision.phase)
                return
            }
            val aod = if (decision.phase == AodHostPhase.DOZING ||
                decision.phase == AodHostPhase.TRANSITIONING) AodIconSettings.current() else null
            if (decision.aodMarksNativeRow && aod != null && !aod.duo) {
                // The opt-in only forces support on unsupported ROMs. Native full-screen AOD
                // devices also need the SystemUI battery row, even when that opt-in is off.
                aodController.showNativeFullAod(binding, aod)
                binding.rowPhase = decision.phase
                return
            }
            if (decision.owner == KeyguardRowOwner.AOD_HOLD) {
                // Legacy AOD draws its own battery row while the keyguard header fades.
                aodController.holdHiddenAod(binding)
                binding.rowPhase = decision.phase
                return
            }
            // Leaving the AOD row: restore native children, row visibility and layout before this
            // pass either renders Duo for a lit lock screen or hides as the host asks.
            aodController.restoreNativeFullAod(binding)
            val percent = (read(battery, "mLevel") as? Int)?.takeIf { read(battery, "mFirstLevel") == false }
            val charging = read(battery, "mCharging") as? Boolean
            val powerSave = read(battery, "mPowerSave") as? Boolean
            // Use the host's configured performance colour, including resource overlays.
            // Charging and power saving retain the native progress-state priority.
            val performanceColor = if (read(battery, "mPerformanceMode") == true)
                read(battery, "mBatteryIconView")?.let { read(it, "mBatteryPerformanceModeColor") as? Int }
                else null
            val freshContent = if (percent != null && charging != null && powerSave != null)
                DuoPolicy.content(
                    DuoBattery(percent, charging, powerSave, performanceColor), mobile, NetworkHub.state.toDuoNetwork(),
                    dataFirst, showTypeWhenDisconnected = aod == null && !hideTypeWhenDisconnected
                ) { subId ->
                    slotIndices[subId] ?: -1
                } else null
            val content = when {
                aod?.centerBattery == true -> percent?.let { value ->
                    DuoContent(DuoBattery(value, charging == true, powerSave == true, performanceColor), null, null,
                        0, false, false)
                } ?: binding.view.icon.content
                aod != null -> freshContent ?: binding.view.icon.content
                else -> presentationContent(binding, freshContent)
            }?.let { resolved ->
                if (aod != null) resolved else {
                    val surface = when (binding.surface) {
                        DuoSurface.HOME, DuoSurface.KEYGUARD, DuoSurface.COLLAPSED_PROXY -> IconSurface.STATUS_BAR
                        DuoSurface.EXPANDED -> IconSurface.CONTROL_CENTER
                        else -> IconSurface.UNKNOWN
                    }
                    val hidden = networkVisibility.hiddenCellularIds(mobile, surface,
                        resolved.wifiLevel != null || mobile.wifiConnected) { slotIndices[it] ?: -1 }
                    DuoPolicy.applyVisibility(
                        resolved, hidden,
                        networkVisibility.hidesWifi(surface, resolved.wifiLevel != null)
                    )
                }
            }?.copy(typography = MobileTypeTypographySettings.current)
            val privacyState = read(binding.parent, "mPrivacyState")?.toString()
            val privacyShowing = DuoPrivacyGeometry.isTransition(privacyState)
            val visible = (read(battery, "mHomeBlock") == false || privacyShowing) && read(battery, "mMinimalism") == false &&
                (binding.surface == DuoSurface.KEYGUARD || read(battery, "mIsAodAnimate") != true) &&
                (aod != null || standardEnabled)
            if (aod != null && binding.failed) {
                aodController.hideForAod(binding)
                binding.rowPhase = decision.phase
                return
            }
            if (aod != null && content == null) {
                // The AOD target has already been selected. Never expose the stock row while
                // battery and network callbacks are still being delivered.
                aodController.hideForAod(binding)
                binding.rowPhase = decision.phase
                return
            }
            val nativeReason = when {
                !enabled -> "disabled"
                binding.failed -> "failed"
                content == null -> "no content battery=${percent != null && charging != null && powerSave != null} " +
                    "subs=${mobile.subscriptionOrder} data=${mobile.activeDataSubId} " +
                    "network=${NetworkHub.state} wifiBound=${NetworkHub.wifiBound}"
                !visible -> "hidden by host"
                battery.parent !== binding.parent -> "reparented"
                !DuoPolicy.replaces(surface(battery), expandedStyle) -> "native surface"
                else -> null
            }
            reportNativeReason(binding, nativeReason)
            if (nativeReason != null) {
                restore(binding)
                binding.rowPhase = decision.phase
                return
            }
            if (content == null) return // Covered by nativeReason; restores the smart cast.
            binding.rowPhase = decision.phase
            binding.view.icon.topIndicatorsEnabled = aod == null
            binding.view.icon.showTopPercent = aod == null && settingDuoTopPercent
            binding.view.icon.cellularDots = aod == null && settingDuoCellularDots
            binding.view.icon.dualSignalDots = aod == null && settingDuoDualDots
            binding.view.setSizes(componentSizes)
            binding.view.icon.foreground = foreground(binding)
            binding.view.icon.nativeWifi = nativeWifiGlyph(binding)
            val networkFont = runCatching {
                MobileTypeHostMetrics.font(battery.context)
            }.getOrNull()
            binding.view.icon.networkTypeface = networkFont?.typeface
            binding.view.icon.networkFontVariationSettings = networkFont?.variationSettings
            val batteryOnly = aod?.centerBattery == true || binding.surface == DuoSurface.EXPANDED &&
                expandedStyle == DuoExpandedStyle.KEEP_DUO
            val percentContainer = read(battery, "mBatteryPercentContainer") as? View
            val percentView = read(battery, "mBatteryPercentView") as? TextView
            val percentMark = read(battery, "mBatteryPercentMarkView") as? TextView
            val showLeadingPercent = DuoPolicy.leadingPercent(binding.surface, expandedStyle,
                settingCcBatteryPercentLeft)
            val batteryGlyph = if (batteryOnly) originalBatteryDrawable(binding) else null
            if (batteryOnly && batteryGlyph == null) {
                if (aod != null) aodController.hideForAod(binding) else restore(binding)
                return
            }
            if (aod != null) {
                renderAodKeyguard(binding, content, aod, batteryGlyph, percentView)
            } else {
                binding.view.layoutDirection = binding.parent.layoutDirection
                binding.view.setBatteryOnly(batteryOnly, batteryGlyph)
                binding.view.setPercent(
                    enabled = showLeadingPercent || batteryOnly,
                    below = batteryOnly,
                    container = percentContainer,
                    value = percentView,
                    mark = percentMark,
                    fallbackText = percent?.toString(),
                    hideMark = true
                )
            }
            binding.view.icon.hideSignalDots = panelHandover.hideExpandedDots(binding)
            if (aod == null) {
                // Keep the same SIM artwork available on either side of a transport switch.
                val cellular = content.copy(wifiLevel = null)
                binding.view.transportBars = signalArtwork.signalBars(battery.context)
                binding.view.transportRows = cellular.cellularSignalLevels.take(2).indices.mapNotNull {
                    signalArtwork.rowMorph(battery.context, it, cellular.cellularSignalLevels.size.coerceAtMost(2))
                }
                binding.view.submit(content, small5GaEnabled,
                    signalArtwork.renderCellularSignal(battery.context, cellular))
                binding.publishKeyguardAirplane()
            }
            binding.view.contentDescription = buildString {
                append(battery.contentDescription?.toString().orEmpty())
                if (content.airplaneMode) {
                    append(", Airplane mode")
                } else {
                    append(", ")
                    val wifi = content.wifiLevelToDraw
                    val signal = content.cellularSignalRows.firstOrNull { it.subId == content.activeDataSubId }
                        ?: content.cellularSignalRows.firstOrNull()
                    content.networkLabel?.let {
                        append(it)
                        if (wifi != null) append(", ")
                    }
                    if (wifi != null) append("Wi-Fi $wifi/4")
                    if (signal != null) append(", SIM ${signal.level.coerceAtLeast(0)}/4")
                    if (content.noInternet) append(", No internet")
                }
            }
            // Charging animates the retained battery into the island (alpha/scale/translation).
            // Those transforms belong to the old battery, not this fixed connectivity cluster.
            binding.view.alpha = if (binding.batteryAnimationToAod != null) battery.alpha else 1f
            binding.view.translationX = privacyOffset(binding, privacyState)
            binding.view.translationY = 0f
            binding.view.scaleX = 1f
            binding.view.scaleY = 1f
            if (!binding.active) {
                if (batteryLayoutField?.get(binding.parent) !== battery) return
                // Establish a measured, visible replacement before claiming the signal mask.
                batteryLayoutField?.set(binding.parent, binding.view)
                binding.active = true
                field(binding.parent.javaClass, "mIsHideBattery")?.setBoolean(binding.parent, false)
                binding.view.visibility = View.VISIBLE
                battery.visibility = View.GONE
                binding.parent.requestLayout()
            }
            binding.view.visibility = View.VISIBLE
            battery.visibility = View.GONE
            binding.aodNativeSuppressed = false
            // During the full capsule the host reserves its width instead of the battery width.
            // Reserve the extra Duo box too, so neighbouring native icons cannot occupy it.
            setPrivacyInset(binding, if (privacyState == "START_SHOW_PRIVACY" ||
                privacyState == "COMPLETE_SHOW_PRIVACY") binding.view.width else 0)
            // Geometry is owned by the parent layout pass, not by callbacks/pre-draw.
            if (binding.view.width > 0 && binding.view.height > 0 && binding.view.isAttachedToWindow) {
                // Expanded KEEP_DUO keeps the battery ring, while native/carrier network rows own
                // their original positions again. Home and the collapsed proxy remain composed.
                val masksNetwork = !(binding.surface == DuoSurface.EXPANDED &&
                    expandedStyle == DuoExpandedStyle.KEEP_DUO)
                if (!SlotMaskApplier.setDuoMask(binding.icons, masksNetwork, binding.planSurface)) {
                    DebugLog.w(TAG, "network slot mask rejected surface=${binding.surface}; showing the native row")
                    binding.failed = true
                    reportNativeReason(binding, "failed")
                    restore(binding)
                } else {
                    // Native SystemUI publishes its own `airplane` status-bar slot.  Duo renders
                    // that state inside the battery ring, so keep the native slot ignored while
                    // the replacement owns this cluster; otherwise two planes appear side by side.
                    setNativeAirplaneMasked(binding, masksNetwork)
                }
            }
        } catch (error: Throwable) {
            binding.failed = true
            restore(binding)
            // A failure is not an AOD state: only an AOD-owned row may stay suppressed, a lit
            // lock screen keeps the host result instead of hiding both representations.
            if (binding.surface == DuoSurface.KEYGUARD && binding.rowPhase != AodHostPhase.LIT) {
                aodController.hideForAod(binding)
            }
            DebugLog.w(TAG, "container failed", error)
        } finally { binding.reconciling = false }
    }

    /**
     * Resolves the host's authoritative dozing source once: `MiuiFullAodManager.isDozing` is
     * written by `StatusBarStateController.StateListener.onDozingChanged`, so it describes the
     * surface that is actually showing. `mToLockScreen` is not used for this decision.
     */
internal fun DuoRenderer.renderAodKeyguard(
        binding: DuoBinding,
        content: DuoContent,
        settings: AodIconSettings,
        batteryGlyph: Drawable?,
        hostPercent: TextView?
    ) {
        val proxy = binding.aodPercent ?: TextView(binding.battery.context).also {
            it.includeFontPadding = false
            binding.aodPercent = it
        }
        proxy.text = if (settings.percentPosition == AodSettingsKeys.AOD_PERCENT_POSITION_DUO_CENTER)
            content.battery.percent.toString() else "${content.battery.percent}%"
        hostPercent?.let {
            proxy.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.textSize)
            proxy.typeface = it.typeface
            proxy.letterSpacing = it.letterSpacing
        }
        proxy.setTextColor(hostPercent?.currentTextColor ?: binding.view.icon.foreground)
        proxy.visibility = View.VISIBLE
        val centerPercent = settings.percentPosition == AodSettingsKeys.AOD_PERCENT_POSITION_DUO_CENTER
        updateAodDuoView(
            context = binding.battery.context,
            target = binding.view,
            content = content,
            centerBattery = settings.centerBattery,
            batteryDrawable = batteryGlyph,
            percentVisible = settings.percent,
            percentBelow = centerPercent,
            percentOnRight = settings.percentPosition == AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT,
            percentContainer = binding.parent,
            percentValue = proxy,
            percentFallback = content.battery.percent.toString(),
            foreground = binding.view.icon.foreground
        )
    }

    /** Keep a valid picture briefly while independent host flows finish a handover. */
internal fun DuoRenderer.presentationContent(binding: DuoBinding, fresh: DuoContent?): DuoContent? {
        if (fresh != null) {
            binding.missingSince = -1L
            binding.expiry?.let(main::removeCallbacks)
            binding.expiry = null
            return fresh
        }
        if (!binding.active) return null
        val now = SystemClock.uptimeMillis()
        if (binding.missingSince < 0L) {
            binding.missingSince = now
            val token = epoch
            binding.expiry = Runnable {
                if (enabled && token == epoch && bindings[binding.battery] === binding) guarded { reconcile(binding) }
            }.also { main.postDelayed(it, DuoHandover.GRACE_MS) }
        }
        return binding.view.icon.content.takeIf { DuoHandover.keepPrevious(binding.missingSince, now) }
    }

    /** Reuse the actual panel progress, but anchor Duo to Duo rather than native signal widths. */

/** Logs each change between Duo and the native row once, with the reason, not every pass. */
internal fun DuoRenderer.reportNativeReason(binding: DuoBinding, reason: String?) {
    // The detail after the reason word changes with every state tick; only the reason dedupes.
    val kind = reason?.substringBefore(' ')
    if (binding.nativeReason == kind) return
    binding.nativeReason = kind
    DebugLog.i(TAG, if (reason == null) "Duo shown surface=${binding.surface}"
        else "Duo native surface=${binding.surface} reason=$reason")
}
