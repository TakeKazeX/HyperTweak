package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.left.LeftRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.*
import com.takekazex.hypertweak.hook.rules.systemui.lockscreen.HideLockscreenStatusBarHooker

/** Composition is lazy: wiring capabilities never constructs a host owner before attachment. */
internal object SystemUiIconPorts {
    fun bind(owner: Any) = IconHostPorts.services.bind(owner, IconHostServices.Binding(
        left = IconHostServices.Left(
            homeSlots = { LeftRenderer.homeOwnedSlots() },
            keyguardSlots = { LeftRenderer.keyguardOwnedSlots() },
            shadeStarted = { LeftRenderer.onShadeSwitchStarted(it) },
            shadeFinished = { LeftRenderer.onShadeSwitchFinished(it) },
            visibilityChanged = { LeftRenderer.requestReconcile() },
            duoOwnershipChanged = { LeftRenderer.onDuoOwnershipChanged() }
        ),
        keyguard = IconHostServices.Keyguard(
            showing = { HideLockscreenStatusBarHooker.keyguardShowing() },
            suppressed = { HideLockscreenStatusBarHooker.statusBarSuppressed() }
        ),
        positions = IconHostServices.Positions(
            carrierMask = { container, mask -> SlotMaskApplier.setCarrierMask(container, mask) },
            lowerRow = { real, fake, _, progress -> SlotMaskApplier.updateLowerRowHandover(real, fake, progress) },
            clearLowerRow = { SlotMaskApplier.clearLowerRowHandover() }
        ),
        carrier = IconHostServices.CarrierLayout(
            owns = { CarrierRenderer.ownsLayout(it) },
            firstCenter = { CarrierRenderer.firstRowCenter(it) },
            ownsRow = { CarrierRenderer.ownsRow(it) },
            geometry = { CarrierRenderer.rowGeometry(it) }
        ),
        duo = IconHostServices.Duo(releaseStatusIcons = { DuoRenderer.releaseLeftStatusIcons(it) }),
        header = IconHostServices.Header(
            supportsCompactLayout = { ControlCenterHeaderHooker.supportsCompactLayout(it) },
            ensureCompactCarrierVisible = { ControlCenterHeaderHooker.ensureCompactCarrierVisible(it) },
            updateCarrierLayout = { carrier, geometryOnly -> ControlCenterHeaderHooker.updateCarrierLayout(carrier, geometryOnly) },
            releaseCarrier = { ControlCenterHeaderHooker.releaseCarrier(it) },
            twoLineConfigured = { ControlCenterHeaderHooker.twoLineConfigured() },
            secondRowStatusIconsEnabled = { ControlCenterHeaderHooker.secondRowStatusIconsEnabled(it) }
        ),
        republish = { IconManagerHooker.republishMergedLists() },
        mobileReady = { MobilePipelineAdapter.mobilePipelineConnected }
    ))
    fun retire(owner: Any) = IconHostPorts.services.retire(owner)
}
