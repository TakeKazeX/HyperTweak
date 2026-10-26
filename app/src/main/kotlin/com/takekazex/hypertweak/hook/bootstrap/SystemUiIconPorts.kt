package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.ControlCenterCarrierBlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.left.LeftContainerHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.*
import com.takekazex.hypertweak.hook.rules.systemui.lockscreen.HideLockscreenStatusBarHooker

/** Composition is lazy: wiring capabilities never constructs a host owner before attachment. */
internal object SystemUiIconPorts {
    fun bind(owner: Any) = IconHostPorts.services.bind(owner, IconHostServices.Binding(
        left = IconHostServices.Left(
            homeSlots = { LeftContainerHooker.homeOwnedSlots() },
            keyguardSlots = { LeftContainerHooker.keyguardOwnedSlots() },
            shadeStarted = { LeftContainerHooker.onShadeSwitchStarted(it) },
            shadeFinished = { LeftContainerHooker.onShadeSwitchFinished(it) },
            visibilityChanged = { LeftContainerHooker.requestReconcile() }
        ),
        keyguard = IconHostServices.Keyguard(
            showing = { HideLockscreenStatusBarHooker.keyguardShowing() },
            suppressed = { HideLockscreenStatusBarHooker.statusBarSuppressed() }
        ),
        positions = IconHostServices.Positions(
            carrierMask = { container, mask -> IconPositionHooker.setCarrierMask(container, mask) },
            lowerRow = { real, fake, alpha, progress -> IconPositionHooker.updateLowerRowHandover(real, fake, alpha, progress) },
            clearLowerRow = { IconPositionHooker.clearLowerRowHandover() }
        ),
        carrier = IconHostServices.CarrierLayout(
            owns = { ControlCenterCarrierBlockHooker.ownsLayout(it) },
            firstCenter = { ControlCenterCarrierBlockHooker.firstRowCenter(it) }
        ),
        republish = { IconManagerHooker.republishMergedLists() },
        mobileReady = { StackedSignalHooker.mobilePipelineConnected }
    ))
    fun retire(owner: Any) = IconHostPorts.services.retire(owner)
}
