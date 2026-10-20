package com.takekazex.hypertweak.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecurityHostRecoveryTest {
    @Test fun stateFreeUpgradeRecoversTheActualPackageOfDottedAndColonHosts() {
        assertEquals("com.miui.securitycenter", SecurityHostRecovery.owner("com.miui.securitycenter.remote", false))
        assertEquals("com.miui.securitycenter", SecurityHostRecovery.owner("com.miui.securitycenter.cache", false))
        assertEquals("com.android.contacts", SecurityHostRecovery.owner("com.android.contacts:business", false))
        assertEquals("com.miui.guardprovider", SecurityHostRecovery.owner("com.miui.guardprovider", false))
        assertEquals("com.miui.guardprovider", SecurityHostRecovery.owner("com.miui.guardprovider:engine", false))
    }

    @Test fun upgradeCannotInferAnUnverifiedPackageFromASimilarProcessName() {
        listOf("com.miui.securitycore", "com.miui.securitycenter.power", "com.miui.securitycenter.remote.other",
            "com.miui.securitycenter:unverified", "com.android.contacts:other", "com.miui.guardprovider.fake",
            "com.miui.networkassistant", "system_server").forEach { assertNull(SecurityHostRecovery.owner(it, false)) }
        assertNull(SecurityHostRecovery.owner("com.miui.securitycenter", true))
    }
}
