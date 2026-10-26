package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.nativebridge.NativeRuleStateSnapshot
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsKeys

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.settings.contract.BackGestureKeys

import com.takekazex.hypertweak.settings.contract.AppSettingsKeys

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups as Groups
import com.takekazex.hypertweak.settings.contract.DockConfig
import org.junit.Assert.*
import org.junit.Test

class RuntimeSettingsRoutingTest {
    @Test fun aospRestorePublishesEverySwitchAndResetToItsExecutingOwner() {
        val keys = mapOf(
            Groups.AOSP_INSTALLER to listOf(PlatformSettingsKeys.KEY_AOSP_PACKAGE_INSTALLER),
            Groups.AOSP_SYSTEM_UI to listOf(PlatformSettingsKeys.KEY_AOSP_POWER_MENU,
                PlatformSettingsKeys.KEY_AOSP_VOLUME_PANEL, PlatformSettingsKeys.KEY_AOSP_VOLUME_HAPTIC_MIUI,
                PlatformSettingsKeys.KEY_AOSP_CLIPBOARD_EDITOR, PlatformSettingsKeys.KEY_EXTEND_UNLOCK_FIX),
            Groups.AOSP_SECURITY to listOf(PlatformSettingsKeys.KEY_AOSP_APP_INFO_ENTRY,
                PlatformSettingsKeys.KEY_AOSP_APP_MANAGER_ENTRY),
        )
        for ((group, switches) in keys) {
            val ledger = RuntimeSettingsLedger()
            val on = RuntimeSettingsSnapshot.read(1, switches.associateWith { true }, setOf(group))!!
            assertTrue(ledger.accept(on))
            val replacement = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
            switches.forEach { key ->
                assertEquals(setOf(group), Groups.affected(key))
                assertEquals(true, replacement.value(key))
                assertNull(RuntimeSettingsSnapshot.read(2, mapOf(key to "true"), setOf(group)))
            }
            assertTrue(replacement.accept(RuntimeSettingsSnapshot.read(2, emptyMap<String, Any>(), setOf(group))!!))
            switches.forEach { assertNull(replacement.value(it)) }
            assertFalse(replacement.accept(on))
        }
        assertTrue(Groups.AOSP_INSTALLER in Groups.forProcess("system_server", true))
        assertTrue(Groups.AOSP_SYSTEM_UI in Groups.forProcess("com.android.systemui"))
        assertTrue(Groups.AOSP_SECURITY in Groups.forProcess("com.miui.securitycenter:ui"))
        assertFalse(Groups.AOSP_INSTALLER in Groups.forProcess("com.android.systemui"))
    }

    @Test fun darkModeScheduleRoutesOnlyToSystemServerAndRestoresAfterReplacementAndReset() {
        val key = PlatformSettingsKeys.KEY_DARK_MODE_IMMEDIATE_SCHEDULE
        val groups = setOf(Groups.DARK_MODE)
        assertEquals(groups, Groups.affected(key))
        assertTrue(Groups.DARK_MODE in Groups.forProcess("system_server", true))
        assertFalse(Groups.owns("com.android.systemui", key))
        assertFalse(Groups.owns("com.android.settings", key))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(key to "true"), groups))
        val ledger = RuntimeSettingsLedger()
        val on = RuntimeSettingsSnapshot.read(1, mapOf(key to true), groups)!!
        assertTrue(ledger.accept(on))
        val replacement = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
        assertEquals(true, replacement.value(key))
        assertTrue(replacement.accept(on.mutate(key, false, 2)!!))
        assertEquals(false, replacement.value(key))
        assertTrue(replacement.accept(RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>(), groups)!!))
        assertNull(replacement.value(key))
        assertFalse(replacement.accept(on))
    }

    @Test fun nativePickerSwitchesMigrateIndependentlyAndResetWithTheirOwnRoutes() {
        val legacy = PlatformSettingsKeys.KEY_NATIVE_PICKER
        val photo = PlatformSettingsKeys.KEY_NATIVE_PHOTO_PICKER
        val file = PlatformSettingsKeys.KEY_NATIVE_FILE_PICKER
        val groups = setOf(Groups.NATIVE_PHOTO_PICKER, Groups.NATIVE_FILE_PICKER)
        assertEquals(groups, Groups.affected(legacy))
        assertEquals(setOf(Groups.NATIVE_PHOTO_PICKER), Groups.affected(photo))
        assertEquals(setOf(Groups.NATIVE_FILE_PICKER), Groups.affected(file))
        assertTrue(Groups.forProcess("system_server", true).containsAll(groups))
        assertEquals(setOf(Groups.NATIVE_FILE_PICKER), Groups.forProcess("com.android.photopicker"))
        assertFalse(Groups.owns("com.android.photopicker", photo))
        assertFalse(Groups.owns("com.android.providers.media.module", file))
        for (key in listOf(legacy, photo, file)) {
            assertNull(RuntimeSettingsSnapshot.read(1, mapOf(key to "true"), groups))
        }
        val ledger = RuntimeSettingsLedger().also {
            it.restore(arrayOf<Any>(1L, hashMapOf(legacy to true), arrayListOf("system.native_picker")))
        }
        assertEquals(true, ledger.value(legacy))
        fun enabled(key: String) = com.takekazex.hypertweak.hook.rules.system.PickerReroutePolicy.enabled(
            ledger.value(key) as? Boolean, ledger.value(legacy) as? Boolean ?: false)
        val on = RuntimeSettingsSnapshot.read(2, mapOf(legacy to true), groups)!!
        assertTrue(ledger.accept(on))
        assertTrue(enabled(photo))
        assertTrue(enabled(file))
        val replacement = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
        assertEquals(true, replacement.value(legacy))
        val photoOff = on.mutate(photo, false, 3)!!
        assertEquals(setOf(Groups.NATIVE_PHOTO_PICKER), ledger.changedGroups(photoOff))
        assertTrue(ledger.accept(photoOff))
        assertFalse(enabled(photo))
        assertTrue(enabled(file))
        assertTrue(ledger.accept(photoOff.mutate(file, false, 4)!!))
        assertFalse(enabled(file))
        assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(5, emptyMap<String, Any>(), groups)!!))
        assertFalse(enabled(photo))
        assertFalse(enabled(file))
        assertFalse(ledger.accept(on))
    }

    @Test fun settingsAiEntriesAreIndependentAndSurviveUpgradeDisableAndReset() {
        val model = PlatformSettingsKeys.KEY_SETTINGS_ONDEVICE_MODEL_ENTRY
        val gemini = PlatformSettingsKeys.KEY_SETTINGS_GEMINI_ENTRY
        val group = setOf(Groups.SETTINGS_AI)
        for (key in listOf(model, gemini)) {
            assertEquals(group, Groups.affected(key))
            assertTrue(Groups.SETTINGS_AI in Groups.forProcess("com.android.settings"))
            assertTrue(Groups.subset("com.android.systemui", mapOf(key to true)).isEmpty())
            assertNull(RuntimeSettingsSnapshot.read(1, mapOf(key to 1), group))
        }
        val ledger = RuntimeSettingsLedger().also {
            it.restore(arrayOf<Any>(1L, hashMapOf("icon_duo_enabled" to true)))
        }
        val enabled = RuntimeSettingsSnapshot.read(2, mapOf(model to true, gemini to true), group)!!
        assertTrue(Groups.SETTINGS_AI in ledger.changedGroups(enabled))
        assertTrue(ledger.accept(enabled))
        val replacement = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
        val geminiOff = enabled.mutate(gemini, false, 3)!!
        assertTrue(replacement.accept(geminiOff))
        assertEquals(true, replacement.value(model))
        assertEquals(false, replacement.value(gemini))
        val reset = RuntimeSettingsSnapshot.read(4, emptyMap<String, Any>(), group)!!
        assertEquals(group, replacement.changedGroups(reset))
        assertTrue(replacement.accept(reset))
        assertNull(replacement.value(model))
        assertNull(replacement.value(gemini))
        assertFalse(replacement.accept(enabled))
    }

    @Test fun forceScreenshotRoutesOnlyToSystemServerAndSurvivesReplacementAndReset() {
        val key = PlatformSettingsKeys.KEY_FORCE_SCREENSHOT
        val groups = setOf(Groups.FORCE_SCREENSHOT)
        assertEquals(groups, Groups.affected(key))
        assertTrue(Groups.owns("android", key))
        assertFalse(Groups.owns("com.miui.screenshot", key))
        assertFalse(Groups.owns("com.android.systemui", key))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(key to 1), groups))
        val ledger = RuntimeSettingsLedger()
        val on = RuntimeSettingsSnapshot.read(1, mapOf(key to true), groups)!!
        assertTrue(ledger.accept(on))
        val replacement = RuntimeSettingsLedger()
        replacement.restore(ledger.save())
        assertEquals(true, replacement.value(key))
        assertTrue(replacement.accept(on.mutate(key, false, 2)!!))
        assertEquals(false, replacement.value(key))
        assertTrue(replacement.accept(RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>(), groups)!!))
        assertNull(replacement.value(key))
        assertFalse(replacement.accept(on))
    }

    @Test fun securityDockSettingsMigrateDisableAndResetTogether() {
        val enabledKey = PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PINNED
        val limitKey = PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_PREDICT_LIMIT
        val displayKey = PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PREDICTIONS
        val group = setOf(Groups.SECURITY_DOCK)
        assertEquals(group, Groups.affected(enabledKey))
        assertEquals(group, Groups.affected(limitKey))
        assertEquals(group, Groups.affected(displayKey))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(displayKey to 1), group))
        assertTrue(Groups.SECURITY_DOCK in Groups.forProcess("com.miui.securitycenter:ui"))
        assertEquals("com.miui.securitycenter", Groups.target("com.miui.securitycenter:ui"))
        assertEquals("com.miui.securitycenter:ui", com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.SecurityCenterDockHost.PROCESS)
        assertFalse(Groups.SECURITY_DOCK in Groups.forProcess("com.miui.home"))
        assertTrue(Groups.subset("com.miui.guardprovider", mapOf(enabledKey to true, limitKey to 15)).isEmpty())
        for (invalid in listOf(-1, 16, "8", 8L)) {
            assertNull(RuntimeSettingsSnapshot.read(1, mapOf(limitKey to invalid), group))
        }
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(enabledKey to "true"), group))
        val ledger = RuntimeSettingsLedger().also {
            it.restore(arrayOf<Any>(1L, hashMapOf("icon_duo_enabled" to true)))
        }
        val on = RuntimeSettingsSnapshot.read(2, mapOf(enabledKey to true, displayKey to true, limitKey to 0), group)!!
        assertTrue(Groups.SECURITY_DOCK in ledger.changedGroups(on))
        assertTrue(ledger.accept(on))
        val migrated = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
        assertEquals(0, migrated.value(limitKey))
        assertEquals(true, migrated.value(displayKey))
        val off = RuntimeSettingsSnapshot.read(3, mapOf(enabledKey to false, limitKey to 15), group)!!
        assertEquals(group, migrated.changedGroups(off))
        assertTrue(migrated.accept(off))
        val reset = RuntimeSettingsSnapshot.read(4, emptyMap<String, Any>(), group)!!
        assertEquals(group, migrated.changedGroups(reset))
        assertTrue(migrated.accept(reset))
        assertNull(migrated.value(limitKey))
        assertNull(migrated.value(displayKey))
    }

    @Test fun sharePanelReachesBothManifestProcessesAndSurvivesUpgradeDisableAndReset() {
        val key = PlatformSettingsKeys.KEY_SHARE_PANEL_SHOW_ALL
        assertEquals(setOf(Groups.SHARE_PANEL), Groups.forProcess("com.android.intentresolver"))
        assertEquals(setOf(Groups.SHARE_PANEL), Groups.forProcess("com.android.intentresolver:chooser"))
        assertEquals(setOf(Groups.SHARE_PANEL), Groups.affected(key))
        assertTrue(Groups.subset("com.miui.gallery", mapOf(key to true)).isEmpty())
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(key to "true"), setOf(Groups.SHARE_PANEL)))
        val ledger = RuntimeSettingsLedger().also {
            it.restore(arrayOf<Any>(1L, hashMapOf("icon_duo_enabled" to true)))
        }
        val enabled = RuntimeSettingsSnapshot.read(2, mapOf(key to true), setOf(Groups.SHARE_PANEL))!!
        assertTrue(Groups.SHARE_PANEL in ledger.changedGroups(enabled))
        assertTrue(ledger.accept(enabled))
        val restored = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
        assertEquals(true, restored.value(key))
        val disabled = RuntimeSettingsSnapshot.read(3, mapOf(key to false), setOf(Groups.SHARE_PANEL))!!
        assertEquals(setOf(Groups.SHARE_PANEL), restored.changedGroups(disabled))
        assertTrue(restored.accept(disabled))
        val reset = RuntimeSettingsSnapshot.read(4, emptyMap<String, Any>(), setOf(Groups.SHARE_PANEL))!!
        assertEquals(setOf(Groups.SHARE_PANEL), restored.changedGroups(reset))
        assertTrue(restored.accept(reset))
        assertNull(restored.value(key))
    }

    @Test fun appDetailsSettingMigratesAndResetsWithoutLeakingToOtherPackages() {
        val key = AppSettingsKeys.KEY_APP_DETAILS_ICON_LAUNCH
        assertEquals(setOf(Groups.APP_DETAILS), Groups.affected(key))
        assertEquals(mapOf(key to true), Groups.subset("com.miui.securitycenter", mapOf(key to true)))
        assertTrue(Groups.subset("com.android.settings", mapOf(key to true)).isEmpty())
        assertTrue(Groups.subset("com.miui.guardprovider", mapOf(key to true)).isEmpty())
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(key to "true"), setOf(Groups.APP_DETAILS)))
        val ledger = RuntimeSettingsLedger().also {
            it.restore(arrayOf<Any>(1L, hashMapOf("icon_duo_enabled" to true)))
        }
        val enabled = RuntimeSettingsSnapshot.read(2, mapOf(key to true), setOf(Groups.APP_DETAILS))!!
        assertTrue(Groups.APP_DETAILS in ledger.changedGroups(enabled))
        assertTrue(ledger.accept(enabled))
        val restored = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
        assertEquals(true, restored.value(key))
        val reset = RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>(), setOf(Groups.APP_DETAILS))!!
        assertEquals(setOf(Groups.APP_DETAILS), restored.changedGroups(reset))
        assertTrue(restored.accept(reset))
        assertNull(restored.value(key))
    }

    @Test fun recentsGestureBarSwitchUsesSystemUiSnapshotAndReset() {
        val key = PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_RECENTS
        assertEquals(setOf(Groups.GESTURE_BAR_UI, Groups.GESTURE_BAR), Groups.affected(key))
        assertEquals(mapOf(key to true), Groups.subset("com.android.systemui", mapOf(key to true)))
        assertEquals(mapOf(key to true), Groups.subset("android", mapOf(key to true)))
        val ledger = RuntimeSettingsLedger()
        assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(1, mapOf(key to true), setOf(Groups.GESTURE_BAR_UI))!!))
        val reset = RuntimeSettingsSnapshot.read(2, emptyMap<String, Any>(), setOf(Groups.GESTURE_BAR_UI))!!
        assertEquals(setOf(Groups.GESTURE_BAR_UI, Groups.GESTURE_BAR), ledger.changedGroups(reset))
        assertTrue(ledger.accept(reset))
        assertNull(ledger.value(key))
    }

    @Test fun launcherGestureBarSwitchReachesSystemUiAndResetsWithItsGroup() {
        val key = PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_LAUNCHER
        // Only SystemUI draws the bar, so the desktop switch has exactly one destination.
        assertEquals(setOf(Groups.GESTURE_BAR_UI), Groups.affected(key))
        assertEquals(mapOf(key to true), Groups.subset("com.android.systemui", mapOf(key to true)))
        assertTrue(Groups.subset("android", mapOf(key to true)).isEmpty())
        val ledger = RuntimeSettingsLedger()
        assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(1, mapOf(key to true), setOf(Groups.GESTURE_BAR_UI))!!))
        assertEquals(true, ledger.value(key))
        val reset = RuntimeSettingsSnapshot.read(2, emptyMap<String, Any>(), setOf(Groups.GESTURE_BAR_UI))!!
        assertEquals(setOf(Groups.GESTURE_BAR_UI), ledger.changedGroups(reset))
        assertTrue(ledger.accept(reset))
        assertNull(ledger.value(key))
    }

    @Test fun allRegisteredPackagesNormalizeColonSubprocessesWithoutInventingUnknownRoutes() {
        Groups.targets.forEach { target ->
            assertEquals(target, Groups.target("$target:worker"))
            assertEquals(Groups.forTarget(target), Groups.forProcess("$target:worker"))
        }
        listOf("com.miui.securitycenter.remote", "com.miui.securitycenter.cache", "com.miui.securitycenter.power")
            .forEach { assertEquals(Groups.forTarget("com.miui.securitycenter"), Groups.forProcess("$it:worker")) }
        assertTrue(Groups.forProcess("com.google.android.gms.unstable").isEmpty())
        assertTrue(Groups.forProcess("android.process.media").isEmpty())
        assertTrue(Groups.forProcess("com.milink.unregistered:worker").isEmpty())
    }

    @Test fun everyExistingLiveRouteHasExplicitConsumersIncludingScreenshotEditorAndSystemServer() {
        assertEquals(setOf(Groups.SCREENSHOT), Groups.forProcess("com.miui.screenshot"))
        assertEquals(Groups.forProcess("com.miui.screenshot"), Groups.forProcess("com.miui.screenshot:photo_editor"))
        assertEquals(setOf(Groups.BACK, Groups.DOCK, Groups.VOLUME, Groups.GESTURE_BAR, Groups.FORCE_SCREENSHOT, Groups.DARK_MODE, Groups.NATIVE_PHOTO_PICKER, Groups.NATIVE_FILE_PICKER, Groups.AOSP_INSTALLER),
            Groups.forProcess("system_server", true))
        assertEquals(setOf(Groups.XMSF), Groups.forProcess("com.xiaomi.xmsf:push"))
        assertTrue(Groups.forProcess("com.miui.home").contains(Groups.NATIVE))
        assertTrue(Groups.forProcess("unrelated.package").isEmpty())
        // The paired recents switches reach both surface ownership and cached drawing policy.
        assertEquals(setOf(Groups.GESTURE_BAR, Groups.GESTURE_BAR_UI), Groups.affected(SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION))
        assertEquals(setOf(Groups.GESTURE_BAR_UI), Groups.affected(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR))
        assertEquals(setOf(Groups.GESTURE_BAR_UI), Groups.affected(SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT))
        val systemUi = Groups.subset("com.android.systemui", mapOf(
            SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION to true,
            PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR to true,
            SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT to true))
        assertEquals(setOf(SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION,
            PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR, SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT),
            systemUi.keys)
        assertEquals(setOf(SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION),
            Groups.subset("android", mapOf(
                SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION to true,
                PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR to true)).keys)
        assertTrue(Groups.forProcess("com.android.systemui").contains(Groups.GESTURE_BAR_UI))
        assertTrue(Groups.forProcess("system_server", true).contains(Groups.GESTURE_BAR))
        assertFalse(Groups.forProcess("system_server", true).contains(Groups.GESTURE_BAR_UI))
    }
    @Test fun eachDestinationReceivesOnlyItsCompleteRegisteredSubset() {
        val values = mapOf<String, Any>(PlatformSettingsKeys.KEY_THREE_ISLANDS to true,
            ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME to true, ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE to 1,
            BackGestureKeys.KEY_BACK_GESTURE_ENABLED to true, PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_COUNT to 50,
            DockConfig.KEY to DockConfig(enabled = true).encode(), "icon_duo_enabled" to true)
        val screenshot = Groups.subset("com.miui.screenshot", values)
        assertEquals(setOf(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME, ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE), screenshot.keys)
        val server = Groups.subset("android", values)
        assertTrue(server.containsKey(DockConfig.KEY))
        assertTrue(server.containsKey(BackGestureKeys.KEY_BACK_GESTURE_ENABLED))
        assertFalse(server.containsKey(PlatformSettingsKeys.KEY_THREE_ISLANDS))
        assertFalse(server.containsKey("icon_duo_enabled"))
    }
    @Test fun oldGenerationSnapshotDoesNotInventDefaultsForNewlyMigratedDomains() {
        val legacy = arrayOf<Any>(11L, hashMapOf("icon_duo_enabled" to true))
        val ledger = RuntimeSettingsLedger().also { it.restore(legacy) }
        assertTrue(ledger.owns("icon_duo_enabled"))
        assertFalse(ledger.owns(PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON))
        assertFalse(ledger.owns(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME))
        assertFalse(ledger.owns(BackGestureKeys.KEY_BACK_GESTURE_ENABLED))
        val updated = RuntimeSettingsSnapshot.read(12, mapOf(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME to true))!!
        assertTrue(ledger.accept(updated))
        assertTrue(ledger.owns(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME))
        assertEquals(true, ledger.value(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME))
    }
    @Test fun processScopedSnapshotsSurviveReplacementWithoutClaimingOtherDomains() {
        val screenshot = RuntimeSettingsSnapshot.read(20, mapOf(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME to true), setOf(Groups.SCREENSHOT))!!
        val old = RuntimeSettingsLedger().also { it.accept(screenshot) }
        val current = RuntimeSettingsLedger().also { it.restore(old.save()) }
        assertTrue(current.owns(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME))
        assertFalse(current.owns(PlatformSettingsKeys.KEY_THREE_ISLANDS))
        assertFalse(current.accept(screenshot.copy(revision = 19)))
        val reset = RuntimeSettingsSnapshot.read(21, emptyMap<String, Any>(), setOf(Groups.SCREENSHOT))!!
        assertEquals(setOf(Groups.SCREENSHOT), current.changedGroups(reset))
        assertTrue(current.accept(reset))
        assertTrue(current.owns(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME))
        assertNull(current.value(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME))
    }
    @Test fun newlyMigratedGroupsAreAppliedEvenWhenTheirCommittedValuesAreAllDefaults() {
        val legacy = RuntimeSettingsLedger().also { it.restore(arrayOf<Any>(1L, hashMapOf("icon_duo_enabled" to true))) }
        val current = RuntimeSettingsSnapshot.read(2L, mapOf("icon_duo_enabled" to true))!!
        assertTrue(legacy.changedGroups(current).containsAll(setOf(Groups.NATIVE, Groups.BACK, Groups.SCREENSHOT)))
        val screenshot = RuntimeSettingsSnapshot.read(3L, emptyMap<String, Any>(), setOf(Groups.SCREENSHOT))!!
        assertNull(screenshot.mutate("icon_duo_enabled", false, 4L))
    }
    @Test fun nativePayloadIdentityAndValuesSurviveTheUnifiedSnapshotAndGenerationHandoff() {
        val native = NativeRuleStateSnapshot(true, 5, true, 23L, 2L, true)
        val snapshot = RuntimeSettingsSnapshot.read(41L, native.settingsValues(), setOf(Groups.NATIVE))!!
        val old = RuntimeSettingsLedger().also { it.accept(snapshot) }
        val restored = RuntimeSettingsLedger().also { it.restore(old.save()) }
        assertEquals(native, NativeRuleStateSnapshot.read { restored.latest()!!.values })
        assertEquals(setOf(Groups.NATIVE), restored.latest()!!.groups)
        assertFalse(restored.owns(BackGestureKeys.KEY_BACK_GESTURE_ENABLED))
    }
    @Test fun invalidScreenshotNativeBackAndDockConfigurationsAreRejectedBeforePublication() {
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE to 2)))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME to "true")))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS to 6)))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(BackGestureKeys.KEY_BACK_GESTURE_ENABLED to "true")))
        assertNull(RuntimeSettingsSnapshot.read(1, mapOf(DockConfig.KEY to "corrupt")))
        assertNull(RuntimeSettingsSnapshot.read(1, emptyMap<String, Any>(), setOf("unregistered")))
    }
    @Test fun commonAcknowledgedMutationPreservesOtherDomainsWhileResetReplacesThemTogether() {
        val original = RuntimeSettingsSnapshot.read(1, mapOf(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME to true,
            BackGestureKeys.KEY_BACK_GESTURE_ENABLED to true, DockConfig.KEY to DockConfig(enabled = true).encode()))!!
        val next = original.mutate(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_COUNT, 30, 2)!!
        assertEquals(true, next.values[ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME])
        assertEquals(true, next.values[BackGestureKeys.KEY_BACK_GESTURE_ENABLED])
        assertEquals(setOf(Groups.VOLUME), Groups.changed(original.values, next.values))
        assertEquals(setOf(Groups.SCREENSHOT, Groups.BACK, Groups.DOCK, Groups.VOLUME),
            Groups.changed(next.values, emptyMap()))
    }
}
