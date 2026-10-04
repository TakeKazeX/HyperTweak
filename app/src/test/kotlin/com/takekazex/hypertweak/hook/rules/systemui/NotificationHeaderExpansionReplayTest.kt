package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.*
import org.junit.Test

class NotificationHeaderExpansionReplayTest {
    class NativeController {
        @JvmField var progress = 1f
        @JvmField var lastSetTextSizeExpansion = 1f
        @JvmField var bigTimeSize = 100
        @JvmField var viewTextSize = 100
        @JvmField var clockScale = 0.2f
        @JvmField val notificationCallback = NativeCallback(this)
        fun updateTranslationY() { clockScale = 20f / bigTimeSize }
    }
    class NativeCallback(private val owner: NativeController) {
        fun onExpansionChanged(progress: Float) {
            owner.progress = progress
            if (owner.lastSetTextSizeExpansion != progress) {
                owner.viewTextSize = if (progress < 0.5f) 20 else owner.bigTimeSize
                owner.lastSetTextSizeExpansion = progress
            }
        }
    }

    @Test fun unchangedExpandedProgressStillUpdatesVisibleFontAndNativeScale() {
        val controller = NativeController()
        controller.bigTimeSize = 200
        controller.notificationCallback.onExpansionChanged(controller.progress)
        assertEquals(100, controller.viewTextSize) // Reproduces native same-progress cache.
        NotificationHeaderExpansionReplay.replay(controller)
        assertEquals(200, controller.viewTextSize)
        assertEquals(0.1f, controller.clockScale, 0.0001f)
        assertEquals(1f, controller.progress, 0f)
    }

    @Test fun retiringGenerationRestoresOriginalSizeAtSameProgress() {
        val controller = NativeController()
        controller.bigTimeSize = 200
        NotificationHeaderExpansionReplay.replay(controller)
        controller.bigTimeSize = 100
        NotificationHeaderExpansionReplay.replay(controller)
        assertEquals(100, controller.viewTextSize)
        assertEquals(0.2f, controller.clockScale, 0.0001f)
    }

    @Test fun collapsedShadePreservesStatusClockSizeAndExpansionProgress() {
        val controller = NativeController()
        controller.progress = 0.25f
        controller.lastSetTextSizeExpansion = 0.25f
        controller.bigTimeSize = 200
        NotificationHeaderExpansionReplay.replay(controller)
        assertEquals(20, controller.viewTextSize)
        assertEquals(0.25f, controller.progress, 0f)
    }
}
