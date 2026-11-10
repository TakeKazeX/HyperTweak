package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups as Groups
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.settings.contract.CameraSettingsKeys as Camera
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys as Platform
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys as SystemUi
import org.junit.Assert.*
import org.junit.Test

class LiveSettingsCompletionTest {
    @Test fun newDomainsReachEveryExecutingProcessAndDoNotLeakToOtherPackages() {
        val routes = mapOf(
            Platform.KEY_POWER_BUTTON_ACTION to setOf("android", "com.google.android.googlequicksearchbox"),
            Platform.KEY_UNLOCK_MORE_VISUAL_PERCEPTION to setOf("android", "com.android.settings"),
            Platform.KEY_UNLOCK_MORE_AON_GESTURES to setOf("android", "com.android.settings"),
            Platform.KEY_UNLOCK_ADAPTIVE_REFRESH_PRO to setOf("android", "com.android.settings"),
            Platform.KEY_SHOW_IN_SETTINGS to setOf("com.android.settings"),
            SystemUi.KEY_IMMEDIATE_MONET_REFRESH to setOf("com.android.systemui"),
        ) + Camera.keys.associateWith { setOf("com.android.camera") }
        routes.forEach { (key, destinations) ->
            assertTrue(key, Groups.owns(key))
            assertEquals(key, destinations, Groups.targets.filter { Groups.owns(it, key) }.toSet())
            destinations.forEach { target -> assertTrue(Groups.forProcess("$target:worker").any { it in Groups.owners(key) }) }
        }
    }

    @Test fun migrationToDefaultValuesStillAppliesNewDomainsAndResetClearsOverrides() {
        val old = RuntimeSettingsSnapshot.read(1, emptyMap<String, Any>(), setOf(Groups.ICONS))!!
        val ledger = RuntimeSettingsLedger().also { it.accept(old) }
        val domains = setOf(Groups.POWER_BUTTON, Groups.EXPERIMENTAL_CAPABILITIES, Groups.CAMERA, Groups.MONET, Groups.MODULE_ENTRY)
        val defaults = RuntimeSettingsSnapshot.read(2, emptyMap<String, Any>(), domains)!!
        assertEquals(domains, ledger.changedGroups(defaults))
        assertTrue(ledger.changedKeys(defaults).isEmpty())
        assertTrue(ledger.accept(defaults))
        val enabled = defaults.mutate(Camera.KEY_CAMERA_WM_CUSTOM, true, 3)!!
        assertEquals(setOf(Groups.CAMERA), ledger.changedGroups(enabled))
        assertTrue(ledger.accept(enabled))
        val replacement = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
        assertEquals(true, replacement.value(Camera.KEY_CAMERA_WM_CUSTOM))
        val reset = RuntimeSettingsSnapshot.read(4, emptyMap<String, Any>(), domains)!!
        assertEquals(setOf(Camera.KEY_CAMERA_WM_CUSTOM), replacement.changedKeys(reset))
        assertTrue(replacement.accept(reset))
        assertNull(replacement.value(Camera.KEY_CAMERA_WM_CUSTOM))
        assertFalse(replacement.accept(enabled))
    }

    @Test fun invalidNewDomainTypesCannotPoisonTheCompleteSnapshot() {
        for (key in Camera.booleanKeys + setOf(Platform.KEY_UNLOCK_MORE_VISUAL_PERCEPTION,
            Platform.KEY_UNLOCK_MORE_AON_GESTURES, Platform.KEY_UNLOCK_ADAPTIVE_REFRESH_PRO,
            Platform.KEY_SHOW_IN_SETTINGS, SystemUi.KEY_IMMEDIATE_MONET_REFRESH)) {
            assertNull(key, RuntimeSettingsSnapshot.read(1, mapOf(key to "true")))
        }
        Camera.stringKeys.forEach { assertNull(RuntimeSettingsSnapshot.read(1, mapOf(it to true))) }
        for (action in listOf(-1, 4, "1", true)) assertNull(RuntimeSettingsSnapshot.read(1, mapOf(Platform.KEY_POWER_BUTTON_ACTION to action)))
        for (action in 0..3) assertNotNull(RuntimeSettingsSnapshot.read(1, mapOf(Platform.KEY_POWER_BUTTON_ACTION to action)))
    }
}
