package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.settings.contract.ControlCenterPrivacyPolicy

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlCenterPrivacyPolicyTest {
    @Test fun `recovery reads prompt types instead of four inflated visible children`() {
        val camera = setOf(ControlCenterPrivacyPolicy.Permission.CAMERA)
        assertEquals(camera, ControlCenterPrivacyPolicy.nativePermissions(intArrayOf(1, 0, 0, 0), null, false))
        assertEquals(emptySet<ControlCenterPrivacyPolicy.Permission>(),
            ControlCenterPrivacyPolicy.nativePermissions(null, null, false))
        assertEquals(camera, ControlCenterPrivacyPolicy.nativePermissions(intArrayOf(1), null, false))
        assertEquals(emptySet<ControlCenterPrivacyPolicy.Permission>(),
            ControlCenterPrivacyPolicy.nativePermissions(intArrayOf(2, -1, 0, 9), null, false))
    }

    @Test fun `android prompt replaces miui types and null android types clear them`() {
        assertEquals(setOf(ControlCenterPrivacyPolicy.Permission.SCREEN),
            ControlCenterPrivacyPolicy.nativePermissions(intArrayOf(1, 1, 1, 1), intArrayOf(0, 0, 0, 1), true))
        assertEquals(emptySet<ControlCenterPrivacyPolicy.Permission>(),
            ControlCenterPrivacyPolicy.nativePermissions(intArrayOf(1, 1, 1, 1), null, true))
    }

    @Test fun `expired history never restores stale native permission snapshot`() {
        val all = ControlCenterPrivacyPolicy.Permission.entries.toSet()
        assertEquals(emptySet<ControlCenterPrivacyPolicy.Permission>(),
            ControlCenterPrivacyPolicy.displayPermissions(emptySet(), all))
        val mic = setOf(ControlCenterPrivacyPolicy.Permission.MICROPHONE)
        assertEquals(mic, ControlCenterPrivacyPolicy.displayPermissions(mic, all))
        assertEquals(mic, ControlCenterPrivacyPolicy.displayPermissions(null, mic))
    }

    @Test fun `centered or offset privacy never reserves status row width after recovery`() {
        for ((centered, offset) in listOf(true to 0, true to 8, false to 8, false to -16)) {
            assertEquals(ControlCenterPrivacyPolicy.Placement.ABOVE_STATUS,
                ControlCenterPrivacyPolicy.placement(true, false, centered, offset))
        }
        assertEquals(ControlCenterPrivacyPolicy.Placement.NATIVE,
            ControlCenterPrivacyPolicy.placement(true, false, false, 0))
    }

    @Test fun `disabled privacy preserves native inline width regardless of old placement values`() {
        for (centered in listOf(false, true)) for (offset in listOf(-16, 0, 8, 24)) {
            for (landscape in listOf(false, true)) {
                assertEquals(ControlCenterPrivacyPolicy.Placement.NATIVE,
                    ControlCenterPrivacyPolicy.placement(false, landscape, centered, offset))
            }
        }
    }

    @Test fun `landscape centers inside the header and rotating back restores portrait placement`() {
        for (centered in listOf(false, true)) for (offset in listOf(-16, 0, 8, 24)) {
            assertEquals(ControlCenterPrivacyPolicy.Placement.CENTER_HEADER,
                ControlCenterPrivacyPolicy.placement(true, true, centered, offset))
            val portrait = if (centered || offset != 0) ControlCenterPrivacyPolicy.Placement.ABOVE_STATUS
                else ControlCenterPrivacyPolicy.Placement.NATIVE
            assertEquals(portrait, ControlCenterPrivacyPolicy.placement(true, false, centered, offset))
        }
    }

    private fun app(uid: Int, pkg: String, label: String, active: Boolean = true) =
        ControlCenterPrivacyPolicy.App(uid, pkg, label, active)

    @Test fun `camera and microphone from the same app produce one name`() {
        assertEquals("Camera & Maps", ControlCenterPrivacyPolicy.names(listOf(
            app(10001, "camera", "Camera"), app(10001, "camera", "Camera"),
            app(10002, "maps", "Maps")), "Native"))
    }

    @Test fun `recently stopped apps do not appear as active`() {
        assertEquals("Maps", ControlCenterPrivacyPolicy.names(listOf(
            app(10001, "camera", "Camera", false), app(10002, "maps", "Maps")), "Native"))
    }

    @Test fun `identical packages in different user profiles remain distinct`() {
        assertEquals("Maps & Work Maps", ControlCenterPrivacyPolicy.names(listOf(
            app(10001, "maps", "Maps"), app(1010001, "maps", "Work Maps")), "Native"))
    }

    @Test fun `missing labels use package and missing active source preserves native summary`() {
        assertEquals("maps", ControlCenterPrivacyPolicy.names(listOf(app(10001, "maps", "")), "Native"))
        assertEquals("Native", ControlCenterPrivacyPolicy.names(listOf(app(10001, "camera", "Camera", false)), "Native"))
    }

    @Test fun `maps scanner and recorder retain all three permissions`() {
        val result = ControlCenterPrivacyPolicy.presentation(listOf(
            app(10001, "maps", "Maps"),
            ControlCenterPrivacyPolicy.App(10002, "scan", "Scanner", true, ControlCenterPrivacyPolicy.Permission.CAMERA),
            ControlCenterPrivacyPolicy.App(10003, "recorder", "Recorder", true, ControlCenterPrivacyPolicy.Permission.MICROPHONE)
        ), "Recorder")
        assertEquals("Maps & Scanner & Recorder", result.names)
        assertEquals(setOf(ControlCenterPrivacyPolicy.Permission.CAMERA,
            ControlCenterPrivacyPolicy.Permission.MICROPHONE, ControlCenterPrivacyPolicy.Permission.LOCATION), result.permissions)
    }

    @Test fun `one app using camera and location has one name and both icons`() {
        val result = ControlCenterPrivacyPolicy.presentation(listOf(
            app(10001, "camera", "Camera"),
            ControlCenterPrivacyPolicy.App(10001, "camera", "Camera", true, ControlCenterPrivacyPolicy.Permission.CAMERA)
        ), "Native")
        assertEquals("Camera", result.names)
        assertEquals(setOf(ControlCenterPrivacyPolicy.Permission.CAMERA,
            ControlCenterPrivacyPolicy.Permission.LOCATION), result.permissions)
    }

    @Test fun `ending recorder use removes only microphone and recorder name`() {
        val result = ControlCenterPrivacyPolicy.presentation(listOf(
            app(10001, "maps", "Maps"),
            ControlCenterPrivacyPolicy.App(10002, "recorder", "Recorder", false, ControlCenterPrivacyPolicy.Permission.MICROPHONE)
        ), "Native")
        assertEquals("Maps", result.names)
        assertEquals(setOf(ControlCenterPrivacyPolicy.Permission.LOCATION), result.permissions)
    }

    @Test fun `projection and microphone from one app retain both permissions`() {
        val result = ControlCenterPrivacyPolicy.presentation(listOf(
            ControlCenterPrivacyPolicy.App(10001, "cast", "Cast", true, ControlCenterPrivacyPolicy.Permission.SCREEN),
            ControlCenterPrivacyPolicy.App(10001, "cast", "Cast", true, ControlCenterPrivacyPolicy.Permission.MICROPHONE)
        ), "Native")
        assertEquals("Cast", result.names)
        assertEquals(setOf(ControlCenterPrivacyPolicy.Permission.SCREEN,
            ControlCenterPrivacyPolicy.Permission.MICROPHONE), result.permissions)
    }

    @Test fun `stopped permission remains until exact fifteen second deadline`() {
        val stopped = ControlCenterPrivacyPolicy.App(10001, "recorder", "Recorder", false,
            ControlCenterPrivacyPolicy.Permission.MICROPHONE, 1000)
        val retained = ControlCenterPrivacyPolicy.presentation(listOf(stopped), "Native", 15999)
        assertEquals("Recorder", retained.names)
        assertEquals(setOf(ControlCenterPrivacyPolicy.Permission.MICROPHONE), retained.permissions)
        assertEquals(16000L, retained.nextExpiryMs)
        val expired = ControlCenterPrivacyPolicy.presentation(listOf(stopped), "Native", 16000)
        assertEquals(emptySet<ControlCenterPrivacyPolicy.Permission>(), expired.permissions)
        assertEquals(null, expired.nextExpiryMs)
    }

    @Test fun `separate stop times expire separately without removing active apps`() {
        val apps = listOf(app(10001, "maps", "Maps"),
            ControlCenterPrivacyPolicy.App(10002, "scanner", "Scanner", false,
                ControlCenterPrivacyPolicy.Permission.CAMERA, 1000),
            ControlCenterPrivacyPolicy.App(10003, "recorder", "Recorder", false,
                ControlCenterPrivacyPolicy.Permission.MICROPHONE, 4000))
        val result = ControlCenterPrivacyPolicy.presentation(apps, "Native", 16000)
        assertEquals("Maps & Recorder", result.names)
        assertEquals(setOf(ControlCenterPrivacyPolicy.Permission.LOCATION,
            ControlCenterPrivacyPolicy.Permission.MICROPHONE), result.permissions)
        assertEquals(19000L, result.nextExpiryMs)
    }

    @Test fun `resuming a permission cancels its old expiry`() {
        val active = ControlCenterPrivacyPolicy.App(10001, "camera", "Camera", true,
            ControlCenterPrivacyPolicy.Permission.CAMERA, 1000)
        val result = ControlCenterPrivacyPolicy.presentation(listOf(active), "Native", 20000)
        assertEquals(setOf(ControlCenterPrivacyPolicy.Permission.CAMERA), result.permissions)
        assertEquals(null, result.nextExpiryMs)
    }

    @Test fun `one app can retain stopped microphone while its projection remains active`() {
        val result = ControlCenterPrivacyPolicy.presentation(listOf(
            ControlCenterPrivacyPolicy.App(10001, "cast", "Cast", true, ControlCenterPrivacyPolicy.Permission.SCREEN),
            ControlCenterPrivacyPolicy.App(10001, "cast", "Cast", false,
                ControlCenterPrivacyPolicy.Permission.MICROPHONE, 1000)), "Native", 15000)
        assertEquals("Cast", result.names)
        assertEquals(setOf(ControlCenterPrivacyPolicy.Permission.SCREEN,
            ControlCenterPrivacyPolicy.Permission.MICROPHONE), result.permissions)
        assertEquals(16000L, result.nextExpiryMs)
    }
}
