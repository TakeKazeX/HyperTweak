package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import android.os.Handler
import android.view.View
import org.junit.Assert.assertNotNull
import org.junit.Test

class LeftCarrierSwapInitializationTest {
    @Test fun rendererSingletonInitializesBeforeHostAttachment() {
        assertNotNull(LeftRenderer.carrierSwap)
        assertNotNull(LeftRenderer.mainHandler)
    }

    @Test fun constructionDoesNotReadTheRenderersNotYetPublishedHandler() {
        // LeftRenderer constructs the controller before initializing its Handler field. Reading
        // it here used to fail the singleton's class initialization in every SystemUI launch.
        val host = object : LeftKeyguardCarrierSwap.Host {
            override val retiring = false
            override val handler: Handler get() = error("Handler accessed before host attachment")
            override val mode = 0
            override val labelKeepsAirplane = true
            override fun iconsShown(state: LeftState) = false
            override fun keyguardStates() = emptyList<LeftState>()
            override fun slotOf(view: View): String? = null
        }
        assertNotNull(LeftKeyguardCarrierSwap(host))
    }
}
