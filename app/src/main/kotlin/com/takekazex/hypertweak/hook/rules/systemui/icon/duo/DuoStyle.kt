@file:Suppress("StaticFieldLeak")
package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.hook.base.orSkip
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.presentation.icon.duo.NativeWifiDrawable

import com.takekazex.hypertweak.presentation.icon.duo.DuoBattery
import com.takekazex.hypertweak.presentation.icon.duo.DuoContent
import com.takekazex.hypertweak.presentation.icon.duo.DuoExpandedStyle
import com.takekazex.hypertweak.presentation.icon.duo.DuoLayout
import com.takekazex.hypertweak.presentation.icon.duo.DuoPolicy
import com.takekazex.hypertweak.presentation.icon.duo.DuoSizes
import com.takekazex.hypertweak.presentation.icon.duo.DuoSurface

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerOptions
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility
import com.takekazex.hypertweak.settings.contract.IconSurface
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelHub
import com.takekazex.hypertweak.hook.rules.systemui.aod.AodIconSettings
import com.takekazex.hypertweak.hook.rules.systemui.icon.SlotMaskApplier
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.presentation.icon.MobileSignalState
import com.takekazex.hypertweak.presentation.icon.toDuoNetwork
import com.takekazex.hypertweak.hook.rules.systemui.icon.NetworkHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.HandoverCoordinator
import com.takekazex.hypertweak.settings.contract.AodSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.CcBatteryPercentStyle
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import kotlin.math.roundToInt
import com.takekazex.hypertweak.util.platform.ResourceLookup

internal fun DuoRenderer.createAodDuoViewImpl(context: Context): View = DuoIconView(context).apply {
        icon.topIndicatorsEnabled = false
        iconSizeDp = DuoLayout.safeSizeDp(
            settingIconDuoSize.toFloat()
        )
        icon.sizes = DuoSizes(
            ring = DuoSizes.ratio(settingIconDuoRingScale),
            wifi = DuoSizes.ratio(settingIconDuoWifiScale),
            cellular = DuoSizes.ratio(settingIconDuoCellularScale),
            type = DuoSizes.ratio(settingIconDuoTypeScale),
            dots = DuoSizes.ratio(settingIconDuoDotsScale),
            airplane = DuoSizes.ratio(settingIconDuoAirplaneScale),
            battery = DuoSizes.ratio(settingIconDuoBatteryScale),
            percent = DuoSizes.ratio(settingIconDuoPercentScale)
        )
    }

    /** Reuses Duo's single/stacked SVG, Wi-Fi routing, battery ring, and centered-percent layout. */
internal fun DuoRenderer.updateAodDuoViewImpl(
        context: Context,
        target: View,
        content: DuoContent,
        centerBattery: Boolean,
        batteryDrawable: Drawable?,
        percentVisible: Boolean,
        percentBelow: Boolean,
        percentOnRight: Boolean,
        percentContainer: View?,
        percentValue: TextView?,
        percentFallback: String?,
        foreground: Int
    ) {
        val duoView = target as? DuoIconView ?: return
        duoView.icon.foreground = foreground
        duoView.icon.sizes = DuoSizes(
            ring = DuoSizes.ratio(settingIconDuoRingScale),
            wifi = DuoSizes.ratio(settingIconDuoWifiScale),
            cellular = DuoSizes.ratio(settingIconDuoCellularScale),
            type = DuoSizes.ratio(settingIconDuoTypeScale),
            dots = DuoSizes.ratio(settingIconDuoDotsScale),
            airplane = DuoSizes.ratio(settingIconDuoAirplaneScale),
            battery = DuoSizes.ratio(settingIconDuoBatteryScale),
            percent = DuoSizes.ratio(settingIconDuoPercentScale)
        )
        duoView.layoutDirection = if (percentOnRight) View.LAYOUT_DIRECTION_RTL
            else View.LAYOUT_DIRECTION_LTR
        duoView.setBatteryOnly(centerBattery, batteryDrawable)
        duoView.setPercent(
            enabled = percentVisible,
            below = percentBelow,
            container = percentContainer,
            value = percentValue,
            mark = null,
            fallbackText = percentFallback
        )
        duoView.submit(
            content,
            settingIconCellularTypeSmall5ga,
            signalArtwork.renderCellularSignal(context, content)
        )
    }

internal fun DuoRenderer.refreshLiveSettings(source: String) {
        if (!enabled) {
            DebugLog.hookSkippedDebug(TAG, "$source settings refresh", "Duo module disabled")
            return
        }
        dataFirst = settingIconMobileRowStyle == 1
        small5GaEnabled = settingIconCellularTypeSmall5ga
        standardEnabled = settingIconDuoEnabled
        expandedStyle = if (settingIconDuoExpanded == 0)
            DuoExpandedStyle.KEEP_DUO else DuoExpandedStyle.RESTORE_NATIVE
        iconSizeDp = DuoLayout.safeSizeDp(
            settingIconDuoSize.toFloat()
        )
        componentSizes = DuoSizes(
            ring = DuoSizes.ratio(settingIconDuoRingScale),
            wifi = DuoSizes.ratio(settingIconDuoWifiScale),
            cellular = DuoSizes.ratio(settingIconDuoCellularScale),
            type = DuoSizes.ratio(settingIconDuoTypeScale),
            dots = DuoSizes.ratio(settingIconDuoDotsScale),
            airplane = DuoSizes.ratio(settingIconDuoAirplaneScale),
            battery = DuoSizes.ratio(settingIconDuoBatteryScale),
            percent = DuoSizes.ratio(settingIconDuoPercentScale)
        )
        val token = epoch
        main.post { if (enabled && token == epoch) guarded {
            bindings.values.toList().forEach { binding ->
                // Capture what is on screen first, then apply, then animate between the two.
                val before = binding.view.settingsState()
                val frame = binding.view.captureSettingsFrame()
                binding.view.setIconSize(iconSizeDp)
                reconcile(binding)
                binding.view.animateSettingsChange(before, frame)
                binding.view.invalidate()
            }
            DebugLog.i(TAG, "$source settings refreshed records=" + bindings.size +
                " standardDuo=" + standardEnabled)
        } }
    }
