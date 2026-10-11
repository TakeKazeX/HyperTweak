package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlCenterPrivacyPolicyTest {
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
