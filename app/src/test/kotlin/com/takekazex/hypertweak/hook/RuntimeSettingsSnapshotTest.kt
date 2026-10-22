package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import org.junit.Assert.*
import org.junit.Test

class RuntimeSettingsSnapshotTest {
    @Test fun completeSnapshotOwnsMissingKeysSoOldRemoteValuesCannotReturn() {
        val ledger = RuntimeSettingsLedger()
        assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(1, mapOf("icon_duo_enabled" to true))!!))
        assertEquals(true, ledger.value("icon_duo_enabled"))
        assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(2, emptyMap<String, Any>())!!))
        assertTrue(ledger.owns("icon_duo_enabled"))
        assertNull(ledger.value("icon_duo_enabled"))
        assertFalse(ledger.owns("unregistered_setting"))
    }
    @Test fun newerSnapshotsSurviveReloadWhileStaleAndConflictingRepliesAreRejected() {
        val current = RuntimeSettingsSnapshot.read(8, mapOf("icon_stacked_enabled" to true,
            "systemui_cc_network_first_row" to false))!!
        val old = RuntimeSettingsLedger().also { assertTrue(it.accept(current)) }
        val restored = RuntimeSettingsLedger().also { it.restore(old.save()) }
        assertFalse(restored.accept(RuntimeSettingsSnapshot.read(7, mapOf("icon_stacked_enabled" to false))!!))
        assertFalse(restored.accept(RuntimeSettingsSnapshot.read(8, mapOf("icon_stacked_enabled" to false))!!))
        assertEquals(true, restored.value("icon_stacked_enabled"))
        assertTrue(restored.accept(current.mutate("systemui_cc_network_first_row", true, 9)!!))
        assertEquals(true, restored.value("systemui_cc_network_first_row"))
    }
    @Test fun acknowledgedMutationDoesNotImportRejectedEditorShadowOrUnrelatedSettings() {
        val baseline = RuntimeSettingsSnapshot.read(1, mapOf("icon_duo_enabled" to true,
            "unregistered_setting" to true, "icon_tuner_position_val" to setOf("0:wifi", "1:mobile")))!!
        val rejectedShadow = baseline.mutate("icon_duo_enabled", false, 2)!!
        val next = baseline.mutate("icon_stacked_enabled", true, 3)!!
        assertEquals(false, rejectedShadow.values["icon_duo_enabled"])
        assertEquals(true, next.values["icon_duo_enabled"])
        assertEquals(true, next.values["icon_stacked_enabled"])
        assertFalse(next.values.containsKey("unregistered_setting"))
        assertEquals(setOf("0:wifi", "1:mobile"), next.values["icon_tuner_position_val"])
    }
    @Test fun islandCommitsReachTheSameLedgerAndResetCannotResurrectRemoteTrue() {
        val ledger = RuntimeSettingsLedger()
        val enabled = RuntimeSettingsSnapshot.read(1, mapOf(Preferences.KEY_THREE_ISLANDS to true,
            Preferences.KEY_ISLAND_NARROW_TIME_FONT to true))!!
        assertTrue(ledger.accept(enabled))
        val disabled = enabled.mutate(Preferences.KEY_THREE_ISLANDS, false, 2)!!
        assertEquals(setOf(RuntimeSettingsGroups.ISLANDS), ledger.changedGroups(disabled))
        assertTrue(ledger.accept(disabled))
        assertEquals(false, ledger.value(Preferences.KEY_THREE_ISLANDS))
        assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>())!!))
        assertTrue(ledger.owns(Preferences.KEY_THREE_ISLANDS))
        assertNull(ledger.value(Preferences.KEY_THREE_ISLANDS))
        assertFalse(ledger.accept(enabled))
    }
    @Test fun settingRegistryRoutesIslandKeysWithoutUnrelatedIconRebuilds() {
        listOf(Preferences.KEY_REMOVE_ISLAND_PLACEHOLDER, Preferences.KEY_THREE_ISLANDS, Preferences.KEY_LANDSCAPE_FULL_ISLANDS,
            Preferences.KEY_LANDSCAPE_MORE_ISLANDS, Preferences.KEY_CC_HIDE_SUPER_ISLAND,
            Preferences.KEY_ISLAND_NARROW_TIME_FONT, Preferences.KEY_ISLAND_LIVE_UPDATE_ICON,
            Preferences.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST,
            Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST).forEach { key ->
            assertTrue(key, RuntimeSettingsSnapshot.owns(key))
            assertEquals(key, setOf(RuntimeSettingsGroups.ISLANDS), RuntimeSettingsGroups.affected(key))
        }
        assertEquals(setOf(RuntimeSettingsGroups.ICONS), RuntimeSettingsGroups.affected("icon_duo_enabled"))
        assertTrue(RuntimeSettingsGroups.affected("unrelated").isEmpty())
        // 12 routing groups before the 2026-10-22 SystemUI live-settings audit added the AOD,
        // keyguard, header, notification-text, media-card, control-center-plugin, glass,
        // lock-screen and power-save domains, then fullscreen capability and transition blur.
        assertEquals(23, RuntimeSettingsGroups.affected(null).size)
        assertTrue(RuntimeSettingsGroups.affected(null).containsAll(setOf(RuntimeSettingsGroups.ICONS, RuntimeSettingsGroups.ISLANDS)))
    }
    @Test fun malformedIncompleteAndOversizedValuesAreRejected() {
        assertNull(RuntimeSettingsSnapshot.read(0, emptyMap<String, Any>()))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf("icon_duo_enabled" to Any())))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf("icon_duo_size" to Float.NaN)))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf("icon_tuner_position_val" to setOf(1))))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf("icon_tuner_stacked_icon_svg_single" to "x".repeat(131073))))
        val strings = mutableSetOf("wifi")
        val snapshot = RuntimeSettingsSnapshot.read(1, mapOf("icon_tuner_position_val" to strings))!!
        strings += "mobile"
        assertEquals(setOf("wifi"), snapshot.values["icon_tuner_position_val"])
    }

    @Test fun securityChecksAndCarrierKeysRouteToTheirHostsAndRequireBooleans() {
        val securityKeys = setOf(
            Preferences.KEY_SECURITY_CENTER_SKIP_LOCAL_SU_CHECK,
            Preferences.KEY_SECURITY_CENTER_DISABLE_SAFEPAY_AUTO_SCAN,
            Preferences.KEY_SECURITY_CENTER_DISABLE_ROOT_SCAN_ITEM,
            Preferences.KEY_SECURITY_CENTER_DISABLE_USB_INSTALL_INTERCEPT,
            Preferences.KEY_SECURITY_CENTER_BYPASS_USB_INSTALL_NETWORK_VERIFY,
        )
        securityKeys.forEach { key ->
            assertEquals(setOf(RuntimeSettingsGroups.SECURITY_CHECKS), RuntimeSettingsGroups.affected(key))
            assertTrue(RuntimeSettingsGroups.owns("com.miui.securitycenter", key))
            assertNull(RuntimeSettingsSnapshot.read(1, mapOf(key to 1)))
        }
        assertEquals(setOf(RuntimeSettingsGroups.SECURITY_CHECKS), RuntimeSettingsGroups.affected(
            Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK
        ))
        assertTrue(RuntimeSettingsGroups.owns("com.miui.guardprovider", Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK))
        assertEquals(setOf(RuntimeSettingsGroups.OFFLINE_CONFIG), RuntimeSettingsGroups.affected(
            Preferences.KEY_NETWORK_ASSISTANT_IGNORE_OFFLINE
        ))
        assertEquals(setOf(RuntimeSettingsGroups.OFFLINE_CONFIG), RuntimeSettingsGroups.affected(
            Preferences.KEY_CONTACTS_BUSINESS_HALL_IGNORE_OFFLINE
        ))
        assertTrue(RuntimeSettingsGroups.owns("com.miui.securitycenter", Preferences.KEY_NETWORK_ASSISTANT_IGNORE_OFFLINE))
        assertTrue(RuntimeSettingsGroups.owns("com.android.contacts", Preferences.KEY_CONTACTS_BUSINESS_HALL_IGNORE_OFFLINE))
        assertEquals("com.miui.securitycenter", RuntimeSettingsGroups.target("com.miui.securitycenter.remote"))
        assertEquals("com.miui.securitycenter", RuntimeSettingsGroups.target("com.miui.securitycenter.cache"))
        assertTrue(RuntimeSettingsGroups.forProcess("com.miui.securitycenter.remote").contains(RuntimeSettingsGroups.OFFLINE_CONFIG))
        assertTrue(RuntimeSettingsGroups.forProcess("com.miui.securitycenter.cache").contains(RuntimeSettingsGroups.OFFLINE_CONFIG))
        assertFalse(RuntimeSettingsGroups.targets.contains("com.miui.networkassistant"))
        assertEquals("com.android.contacts", RuntimeSettingsGroups.target("com.android.contacts:business"))
    }

    @Test fun upgradeFromOldGroupsAddsNewRoutesAndPreservesThePersistedGuardProviderValue() {
        val oldGroups = setOf(RuntimeSettingsGroups.ICONS, RuntimeSettingsGroups.ISLANDS)
        val legacy = RuntimeSettingsSnapshot.read(1, mapOf("icon_duo_enabled" to true), oldGroups)!!
        val restored = RuntimeSettingsLedger().also {
            it.restore(arrayOf(1L, hashMapOf("icon_duo_enabled" to true), oldGroups.toList()))
        }
        assertEquals(true, restored.value("icon_duo_enabled"))
        val upgraded = RuntimeSettingsSnapshot.read(2, mapOf(
            "icon_duo_enabled" to true,
            Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK to true,
            Preferences.KEY_SECURITY_CENTER_SKIP_LOCAL_SU_CHECK to false,
            Preferences.KEY_CONTACTS_BUSINESS_HALL_IGNORE_OFFLINE to false,
        ))!!
        assertEquals(setOf(RuntimeSettingsGroups.SECURITY_CHECKS, RuntimeSettingsGroups.OFFLINE_CONFIG),
            RuntimeSettingsGroups.changed(legacy.values, upgraded.values))
        assertTrue(restored.accept(upgraded))
        assertEquals(true, restored.value(Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK))
        assertEquals(false, restored.value(Preferences.KEY_SECURITY_CENTER_SKIP_LOCAL_SU_CHECK))
        val reset = RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>())!!
        assertTrue(restored.accept(reset))
        assertTrue(restored.owns(Preferences.KEY_SECURITY_CENTER_SKIP_LOCAL_SU_CHECK))
        assertNull(restored.value(Preferences.KEY_SECURITY_CENTER_SKIP_LOCAL_SU_CHECK))
        assertNull(RuntimeSettingsSnapshot.read(4, mapOf(
            Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK to "true"
        )))
    }
    @Test fun dropdownSelectionKeepsBothFlagsInOneAcknowledgedGeneration() {
        val indicator = Preferences.KEY_BACK_GESTURE_HYPEROS_INDICATOR
        val material = Preferences.KEY_BACK_GESTURE_INDICATOR_BLUR
        val baseline = RuntimeSettingsSnapshot.read(1, mapOf(indicator to false, material to false))!!
        val selection = RuntimeSettingsSnapshot.read(2, baseline.values + mapOf(indicator to true, material to true))!!
        val ledger = RuntimeSettingsLedger()
        assertTrue(ledger.accept(baseline))
        assertEquals(setOf(RuntimeSettingsGroups.BACK), ledger.changedGroups(selection))
        assertTrue(ledger.accept(selection))
        val replacement = RuntimeSettingsLedger()
        replacement.restore(ledger.save())
        assertEquals(true, replacement.value(indicator))
        assertEquals(true, replacement.value(material))
        // A delayed intermediate/failed edit cannot turn the restored material back off.
        assertFalse(replacement.accept(baseline.mutate(indicator, true, 2)!!))
        assertTrue(replacement.accept(RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>())!!))
        assertNull(replacement.value(indicator))
        assertNull(replacement.value(material))
    }

}
