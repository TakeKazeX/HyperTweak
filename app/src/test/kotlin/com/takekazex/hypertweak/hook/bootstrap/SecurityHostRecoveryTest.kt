package com.takekazex.hypertweak.hook.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecurityHostRecoveryTest {
    @Test fun stateFreeUpgradeRecoversTheActualPackageOfDottedAndColonHosts() {
        assertEquals("com.miui.securitycenter", SecurityHostRecovery.owner("com.miui.securitycenter:ui", false))
        assertEquals("com.miui.securitycenter", SecurityHostRecovery.owner("com.miui.securitycenter.remote", false))
        assertEquals("com.miui.securitycenter", SecurityHostRecovery.owner("com.miui.securitycenter.cache", false))
        assertEquals("com.miui.securitycenter", SecurityHostRecovery.owner("com.miui.securitycenter.power", false))
        assertEquals("com.miui.securitycenter", SecurityHostRecovery.owner("com.miui.securitycenter.bootaware", false))
        assertEquals("com.miui.securitycenter", SecurityHostRecovery.owner("com.miui.securitycenter.uniauth", false))
        assertEquals("com.miui.securitycenter", SecurityHostRecovery.owner("com.miui.securitycenter:cache", false))
        assertEquals("com.miui.securitycore", SecurityHostRecovery.owner("com.miui.securitycore", false))
        assertEquals("com.miui.securitycore", SecurityHostRecovery.owner("com.miui.securitycore:worker", false))
        assertEquals("com.lbe.security.miui", SecurityHostRecovery.owner("com.lbe.security.miui", false))
        assertEquals("com.lbe.security.miui", SecurityHostRecovery.owner("com.lbe.security.miui:worker", false))
        assertEquals("com.miui.securitycenter", SecurityHostRecovery.owner("com.miui.securitycenter:aux", false))
        assertEquals("com.android.contacts", SecurityHostRecovery.owner("com.android.contacts:business", false))
        assertEquals("com.miui.guardprovider", SecurityHostRecovery.owner("com.miui.guardprovider", false))
        assertEquals("com.miui.guardprovider", SecurityHostRecovery.owner("com.miui.guardprovider:engine", false))
    }

    @Test fun upgradeCannotInferAnUnverifiedPackageFromASimilarProcessName() {
        listOf("com.miui.securitycore.fake", "com.lbe.security.miui.fake", "com.miui.securitycenter.remote.other",
            "com.miui.securitycenter:", "com.android.contacts:other", "com.miui.guardprovider.fake",
            "com.miui.networkassistant", "system_server").forEach { assertNull(SecurityHostRecovery.owner(it, false)) }
        assertNull(SecurityHostRecovery.owner("com.miui.securitycenter", true))
    }
}
