package com.takekazex.hypertweak.hook.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppHostRecoveryTest {
    @Test fun stateFreeUpgradeRecoversTheActualPackageOfDottedAndColonHosts() {
        assertEquals("com.miui.securitycenter", AppHostRecovery.owner("com.miui.securitycenter:ui", false))
        assertEquals("com.miui.securitycenter", AppHostRecovery.owner("com.miui.securitycenter.remote", false))
        assertEquals("com.miui.securitycenter", AppHostRecovery.owner("com.miui.securitycenter.cache", false))
        assertEquals("com.miui.securitycenter", AppHostRecovery.owner("com.miui.securitycenter.power", false))
        assertEquals("com.miui.securitycenter", AppHostRecovery.owner("com.miui.securitycenter.bootaware", false))
        assertEquals("com.miui.securitycenter", AppHostRecovery.owner("com.miui.securitycenter.uniauth", false))
        assertEquals("com.miui.securitycenter", AppHostRecovery.owner("com.miui.securitycenter:cache", false))
        assertEquals("com.miui.securitycore", AppHostRecovery.owner("com.miui.securitycore", false))
        assertEquals("com.miui.securitycore", AppHostRecovery.owner("com.miui.securitycore:worker", false))
        assertEquals("com.lbe.security.miui", AppHostRecovery.owner("com.lbe.security.miui", false))
        assertEquals("com.lbe.security.miui", AppHostRecovery.owner("com.lbe.security.miui:worker", false))
        assertEquals("com.miui.securitycenter", AppHostRecovery.owner("com.miui.securitycenter:aux", false))
        assertEquals("com.xiaomi.barrage", AppHostRecovery.owner("com.xiaomi.barrage", false))
        assertEquals("com.android.contacts", AppHostRecovery.owner("com.android.contacts:business", false))
        assertEquals("com.miui.guardprovider", AppHostRecovery.owner("com.miui.guardprovider", false))
        assertEquals("com.miui.guardprovider", AppHostRecovery.owner("com.miui.guardprovider:engine", false))
    }

    @Test fun purificationHostsRecoverWithoutPriorFeatureOrPackageState() {
        for ((owner, processes) in mapOf(
            "com.xiaomi.market" to listOf("com.xiaomi.market", "com.xiaomi.market:pushservice", "com.xiaomi.market:guard",
                "com.xiaomi.market:widgetProvider", "com.xiaomi.market:cloudProfileCollect"),
            "com.xiaomi.vipaccount" to listOf("com.xiaomi.vipaccount", "com.xiaomi.vipaccount:pushservice"),
        )) {
            processes.forEach { process ->
                assertEquals(owner, AppHostRecovery.owner(process, false))
                assertNull(AppHostRecovery.owner(process, true))
            }
            assertNull(AppHostRecovery.owner("$owner:", false))
            assertNull(AppHostRecovery.owner("$owner.fake", false))
        }
    }

    @Test fun upgradeCannotInferAnUnverifiedPackageFromASimilarProcessName() {
        listOf("com.miui.securitycore.fake", "com.lbe.security.miui.fake", "com.miui.securitycenter.remote.other",
            "com.miui.securitycenter:", "com.android.contacts:other", "com.miui.guardprovider.fake",
            "com.miui.networkassistant", "system_server").forEach { assertNull(AppHostRecovery.owner(it, false)) }
        assertNull(AppHostRecovery.owner("com.miui.securitycenter", true))
    }
}
