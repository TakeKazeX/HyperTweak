package com.takekazex.hypertweak.hook.rules.googleapp

import org.junit.Assert.*
import org.junit.Test

class GoogleAppProcessPolicyTest {
    @Test fun `only the manifest UI process owns Google feature setup`() {
        val pkg = GoogleAppProcessPolicy.PACKAGE
        assertTrue(GoogleAppProcessPolicy.ownsUi(pkg, "$pkg:googleapp"))
        assertFalse(GoogleAppProcessPolicy.shouldSkipProcess("$pkg:googleapp"))
        for (process in listOf(pkg, "$pkg:interactor", "$pkg:search", "$pkg:unknown")) {
            assertTrue(process, GoogleAppProcessPolicy.shouldSkipProcess(process))
            assertFalse(process, GoogleAppProcessPolicy.ownsUi(pkg, process))
        }
    }

    @Test fun `other hosts and similar package prefixes are not excluded`() {
        val pkg = GoogleAppProcessPolicy.PACKAGE
        for (process in listOf("com.android.systemui", "system_server", "com.miui.home", "${pkg}other")) {
            assertFalse(process, GoogleAppProcessPolicy.shouldSkipProcess(process))
        }
        assertFalse(GoogleAppProcessPolicy.ownsUi("other.package", "$pkg:googleapp"))
    }
}
