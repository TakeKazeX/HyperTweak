package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups as Groups
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys as Keys
import org.junit.Assert.*
import org.junit.Test

class SecurityCenterRuntimeSettingsTest {
    private val privacy = listOf(Keys.KEY_SECURITY_CENTER_PRIVACY_CALL_ENTRY,
        Keys.KEY_SECURITY_CENTER_FRONT_CAMERA_ASSISTANT_ENTRY, Keys.KEY_SECURITY_CENTER_CALL_PRIVACY_CAMERA_ENTRY)
    private val warnings = listOf(Keys.KEY_SECURITY_CENTER_LOW_BATTERY_MODE,
        Keys.KEY_SECURITY_CENTER_PERMISSION_WARNING_MODE, Keys.KEY_SECURITY_CENTER_ANTI_PEEPING_MODE)
    private val gated = listOf(Keys.KEY_SECURITY_CENTER_SHOW_BERSERK_MODE,
        Keys.KEY_SECURITY_CENTER_SHOW_DETAILED_POWER_DATA, Keys.KEY_SECURITY_CENTER_RESTORE_POWER_RANKING,
        Keys.KEY_SECURITY_CENTER_MORE_BATTERY_INFO, Keys.KEY_POWER_SAVE_OVERRIDE_MASTER,
        Keys.KEY_KEEP_REFRESH_RATE_IN_POWER_SAVE, Keys.KEY_KEEP_HAPTIC_FEEDBACK_IN_POWER_SAVE,
        Keys.KEY_KEEP_SYSTEM_SOUNDS_IN_POWER_SAVE, Keys.KEY_KEEP_WAKEUP_GESTURES_IN_POWER_SAVE)

    @Test fun routesEveryOwnerToItsExecutingPackagesAndManifestProcesses() {
        val center = "com.miui.securitycenter"
        privacy.forEach { assertEquals(setOf(Groups.SECURITY_PRIVACY), Groups.affected(it)) }
        assertEquals(setOf(Groups.SECURITY_PRIVACY), Groups.affected(Keys.KEY_SECURITY_CENTER_ANTI_PEEPING_MODE))
        listOf(Keys.KEY_SECURITY_CENTER_LOW_BATTERY_MODE, Keys.KEY_SECURITY_CENTER_HIDE_LOW_BATTERY_WARNING,
            Keys.KEY_SECURITY_CENTER_PERMISSION_WARNING_MODE).forEach {
            assertEquals(setOf(Groups.SECURITY_WARNINGS), Groups.affected(it))
        }
        gated.forEach { assertTrue(Groups.GATED_FEATURES in Groups.affected(it)) }
        val clipboard = Keys.KEY_LBE_CLIPBOARD_TOAST
        val bubble = Keys.KEY_SECURITY_CENTER_BUBBLE_NOTIFICATION_UNLOCK
        listOf(center, "com.lbe.security.miui").forEach { assertTrue(Groups.owns(it, clipboard)) }
        listOf(center, "com.miui.securitycore", "com.android.systemui").forEach { assertTrue(Groups.owns(it, bubble)) }
        assertFalse(Groups.owns("com.miui.securitycore", clipboard))
        assertFalse(Groups.owns("com.lbe.security.miui", bubble))
        listOf(center, "$center:ui", "$center:cache", "$center.remote", "$center.cache",
            "$center.power", "$center.bootaware", "$center.uniauth").forEach {
            assertEquals(Groups.forTarget(center), Groups.forProcess(it))
        }
        assertFalse(Groups.owns("$center.unknown", clipboard))
    }

    @Test fun acceptedSnapshotsSurviveReplacementDisableAndResetAndRejectMalformedValues() {
        val booleanKeys = privacy + gated + listOf(Keys.KEY_SECURITY_CENTER_HIDE_LOW_BATTERY_WARNING,
            Keys.KEY_SECURITY_CENTER_BUBBLE_NOTIFICATION_UNLOCK, Keys.KEY_LBE_CLIPBOARD_TOAST)
        val groups = setOf(Groups.SECURITY_PRIVACY, Groups.SECURITY_WARNINGS, Groups.GATED_FEATURES,
            Groups.SECURITY_BUBBLES, Groups.SECURITY_CLIPBOARD)
        booleanKeys.forEach { assertNull(RuntimeSettingsSnapshot.read(1, mapOf(it to "true"), groups)) }
        warnings.forEach { key ->
            assertNull(RuntimeSettingsSnapshot.read(1, mapOf(key to true), groups))
            assertNull(RuntimeSettingsSnapshot.read(1, mapOf(key to 3), groups))
            assertNull(RuntimeSettingsSnapshot.read(1, mapOf(key to -1), groups))
        }
        val values = booleanKeys.associateWith { true } + warnings.associateWith { 2 }
        val ledger = RuntimeSettingsLedger()
        // Old snapshots did not own any of the new domains; their absent keys cannot invent overrides.
        ledger.restore(arrayOf<Any>(1L, hashMapOf("icon_duo_enabled" to true)))
        val enabled = RuntimeSettingsSnapshot.read(2, values, groups)!!
        assertTrue(ledger.changedGroups(enabled).containsAll(groups))
        assertTrue(ledger.accept(enabled))
        val replacement = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
        assertEquals(true, replacement.value(Keys.KEY_LBE_CLIPBOARD_TOAST))
        assertEquals(2, replacement.value(Keys.KEY_SECURITY_CENTER_ANTI_PEEPING_MODE))
        val disabled = enabled.mutate(Keys.KEY_LBE_CLIPBOARD_TOAST, false, 3)!!
        assertEquals(setOf(Groups.SECURITY_CLIPBOARD), replacement.changedGroups(disabled))
        assertTrue(replacement.accept(disabled))
        val reset = RuntimeSettingsSnapshot.read(4, emptyMap<String, Any>(), groups)!!
        assertTrue(replacement.accept(reset))
        booleanKeys.forEach { assertNull(replacement.value(it)) }
        warnings.forEach { assertNull(replacement.value(it)) }
        assertFalse(replacement.accept(enabled))
    }
}
