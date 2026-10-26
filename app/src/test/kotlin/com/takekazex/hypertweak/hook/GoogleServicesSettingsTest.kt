package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups as Groups
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys as Keys
import org.junit.Assert.*
import org.junit.Test

class GoogleServicesSettingsTest {
    @Test fun allSevenSwitchesRouteToEveryExecutingOwnerAndSurviveUpgradeDisableReset() {
        val routes = mapOf(
            Keys.KEY_ASK_ABOUT_SCREEN to setOf("com.google.android.googlequicksearchbox"),
            Keys.KEY_FULL_SCREEN_TRANSLATE to setOf("com.google.android.googlequicksearchbox"),
            Keys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS to setOf("com.android.settings"),
            Keys.KEY_REMOVE_GMS_RESTRICTION to setOf("android"),
            Keys.KEY_QUICK_SHARE_ENABLED to setOf("com.google.android.gms"),
            Keys.KEY_UNLOCK_PASSKEY to setOf("android", "com.android.settings", "com.miui.securitycenter", "com.xiaomi.scanner"),
            Keys.KEY_FCM_LIVE_ENABLED to setOf("android", "com.miui.powerkeeper"),
        )
        val all = routes.keys.associateWith { true }
        routes.forEach { (key, owners) ->
            assertFalse(Groups.affected(key).isEmpty())
            assertEquals(owners, Groups.targets.filter { Groups.owns(it, key) }.toSet())
            for (owner in owners) {
                val process = if (owner == "android") "system_server" else "$owner:worker"
                assertEquals(owner, Groups.target(process))
                val groups = Groups.forProcess(process)
                assertNull(RuntimeSettingsSnapshot.read(1, mapOf(key to "true"), groups))
                val ledger = RuntimeSettingsLedger()
                ledger.restore(arrayOf<Any>(1L, hashMapOf("icon_duo_enabled" to true)))
                assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(2, Groups.subset(owner, all), groups)!!))
                assertEquals(true, ledger.value(key))
                val replacement = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
                assertTrue(replacement.accept(replacement.latest()!!.mutate(key, false, 3)!!))
                assertEquals(false, replacement.value(key))
                assertTrue(replacement.accept(RuntimeSettingsSnapshot.read(4, emptyMap<String, Any>(), groups)!!))
                assertNull(replacement.value(key))
                assertFalse(replacement.accept(RuntimeSettingsSnapshot.read(2, mapOf(key to true), groups)!!))
            }
        }
        assertTrue(Groups.GOOGLE_APP in Groups.forProcess("com.google.android.googlequicksearchbox:googleapp"))
        assertTrue(Groups.PASSKEY in Groups.forProcess("com.miui.securitycenter.remote"))
        assertTrue(Groups.QUICK_SHARE in Groups.forProcess("com.google.android.gms.persistent"))
    }
}
