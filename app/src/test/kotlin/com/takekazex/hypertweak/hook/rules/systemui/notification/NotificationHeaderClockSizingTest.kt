package com.takekazex.hypertweak.hook.rules.systemui.notification

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import org.junit.Assert.*
import org.junit.Test

class NotificationHeaderClockSizingTest {
    class HostResources : Resources(null, null, null) {
        private val config = Configuration().apply { orientation = Configuration.ORIENTATION_PORTRAIT }
        override fun getConfiguration() = config
        override fun getIdentifier(name: String, defType: String?, defPackage: String?): Int =
            if (name == "shade_header_notification_clock_text_size") 1 else 2
        override fun getDimensionPixelSize(id: Int) = if (id == 1) 100 else 80
    }

    class HostController {
        @JvmField var bigTimeSize = 240 // Another module's size, distinct from the native resource.
        @JvmField var shadeHeaderNotificationClockTextSize = 240
        @JvmField var qsControlHeaderClockFlipSize = 160
        @JvmField val context: Context = object : ContextWrapper(null) {
            private val hostResources = HostResources()
            override fun getResources() = hostResources
        }
    }

    private fun sizing(scale: () -> Float) = NotificationHeaderClockSizing(
        field = { type, name -> type.getDeclaredField(name) },
        read = { owner, name -> owner.javaClass.getDeclaredField(name).get(owner) },
        resolveClass = { null },
        timeScale = scale,
        reportFailure = { throw AssertionError(it) }
    )

    @Test fun defaultScaleNeverClaimsOrRestoresAnotherModulesSize() {
        val controller = HostController()
        val sizing = sizing { 1f }
        sizing.applyClockSize(controller)
        assertEquals(240, controller.bigTimeSize)
        assertTrue(sizing.controllerSizes.isEmpty())
        assertEquals(listOf(controller), sizing.controllers())
        controller.bigTimeSize = 260
        sizing.restore()
        assertEquals(260, controller.bigTimeSize)
    }

    @Test fun enableResetReenableAndRetirementKeepThePreexistingBaseline() {
        val controller = HostController()
        var scale = 1f
        val sizing = sizing { scale }
        sizing.applyClockSize(controller)
        scale = 2f
        sizing.applyClockSize(controller)
        assertEquals(200, controller.bigTimeSize)
        sizing.applyClockSize(controller)
        scale = 1f
        sizing.applyClockSize(controller)
        assertEquals(240, controller.bigTimeSize)
        assertEquals(160, controller.qsControlHeaderClockFlipSize)
        assertTrue(sizing.controllerSizes.isEmpty())
        controller.bigTimeSize = 260
        scale = 1.5f
        sizing.applyClockSize(controller)
        assertEquals(150, controller.bigTimeSize)
        sizing.restore()
        assertEquals(260, controller.bigTimeSize)
        assertTrue(sizing.controllerSizes.isEmpty())
        sizing.clear()
        assertTrue(sizing.controllers().isEmpty())
    }
}
