package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups as Groups
import com.takekazex.hypertweak.hook.base.SettingsReloadDispatcher
import org.junit.Assert.*
import org.junit.Test

class RuntimeSettingsDeltaReloadTest {
    private fun snapshot(revision: Long, values: Map<String, Any>) =
        requireNotNull(RuntimeSettingsSnapshot.read(revision, values))

    @Test fun `full native transaction with an epoch materialization never rebuilds unchanged icons`() {
        val before = mapOf<String, Any>("icon_duo_enabled" to true, PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC to false)
        val ledger = RuntimeSettingsLedger().also { it.accept(snapshot(1, before)) }
        val next = snapshot(2, before + mapOf(PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC to true,
            PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION to 101L, "prefs_epoch" to 0L))
        val jobs = linkedSetOf<Runnable>()
        var icons = 0
        var native = 0
        val dispatcher = SettingsReloadDispatcher({ work, _ -> jobs += work },
            { jobs.remove(it) }, { _, _ -> fail("reload failed") })
        dispatcher.register(Groups.ICONS) { icons++; true }
        dispatcher.register(Groups.NATIVE) { native++; true }
        fun receive(value: RuntimeSettingsSnapshot) {
            val changed = ledger.changedGroups(value)
            if (ledger.accept(value)) dispatcher.request(changed)
        }
        receive(next)
        repeat(30) { receive(next) } // Preference-listener resync and duplicate transports.
        receive(next.copy(revision = 3))
        jobs.toList().forEach(Runnable::run)
        assertEquals(1, native)
        assertEquals(0, icons)
    }

    @Test fun `reset epoch invalidates ordering without repainting unchanged feature values`() {
        val old = mapOf<String, Any>("icon_duo_enabled" to false, "prefs_epoch" to 1L,
            PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC to true, PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION to 10L)
        val reset = old + mapOf("prefs_epoch" to 2L, PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC to false,
            PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION to 11L)
        assertEquals(setOf(Groups.NATIVE), Groups.changed(old, reset))
        assertTrue(Groups.changed(reset, reset + mapOf("prefs_epoch" to 3L,
            PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION to 12L)).isEmpty())
        assertEquals(setOf(Groups.ICONS), Groups.changed(reset, reset + ("icon_duo_enabled" to true)))
    }

    @Test fun `independent domains still apply together and actual reset removals stay visible`() {
        val old = mapOf<String, Any>("icon_duo_enabled" to true,
            PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC to true, PlatformSettingsKeys.KEY_THREE_ISLANDS to true,
            "prefs_epoch" to 1L)
        assertEquals(setOf(Groups.ICONS, Groups.NATIVE, Groups.ISLANDS), Groups.changed(old, emptyMap()))
        val next = old + mapOf(PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC to false,
            PlatformSettingsKeys.KEY_THREE_ISLANDS to false)
        assertEquals(setOf(Groups.NATIVE, Groups.ISLANDS), Groups.changed(old, next))
    }
}
