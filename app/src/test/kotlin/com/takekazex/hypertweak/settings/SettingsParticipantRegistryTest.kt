package com.takekazex.hypertweak.settings

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import com.takekazex.hypertweak.hook.base.SettingsReloadDispatcher
import com.takekazex.hypertweak.hook.bootstrap.RuntimeSettingsParticipants
import org.junit.Assert.*
import org.junit.Test

class SettingsParticipantRegistryTest {
    @Test fun dormantImeParticipantSurvivesAnInitiallyUnselectedProcess() {
        val plan = SettingsParticipantRegistry(emptySet())
        var applied = false
        plan.register("ime") { applied = true; true }
        var handler: ((Set<String>) -> Boolean)? = null
        plan.install(always = setOf("ime")) { id, apply ->
            assertEquals("ime", id)
            handler = apply
        }
        assertFalse(applied)
        assertTrue(requireNotNull(handler)(setOf("aosp_ime_packages")))
        assertTrue(applied)
    }
    @Test fun missingParticipantFailsBeforeAnyLiveRegistration() {
        val plan = SettingsParticipantRegistry(setOf("icons", "native"))
        plan.register("icons") { true }
        var registrations = 0
        assertThrows(IllegalStateException::class.java) { plan.install { _, _ -> registrations++ } }
        assertEquals(0, registrations)
    }

    @Test fun duplicateAndForeignProcessHandlersCannotSilentlyReplaceOwners() {
        val plan = SettingsParticipantRegistry(setOf("icons"))
        val seen = mutableListOf<String>()
        plan.register("native") { error("foreign process handler invoked") }
        plan.register("icons") { keys -> seen += keys; true }
        assertThrows(IllegalStateException::class.java) { plan.register("icons") { false } }
        plan.install { id, apply -> assertEquals("icons", id); assertTrue(apply(setOf("icon_duo_enabled"))) }
        assertEquals(listOf("icon_duo_enabled"), seen)
    }

    @Test fun everyRealProcessPlanCoversItsSchemaWithoutInitializingOrApplyingHooks() {
        val unexpected = { error("registration must not apply host effects") }
        val actions = RuntimeSettingsParticipants.Actions(
            icons = { unexpected() }, publishNative = { unexpected() }, aod = { unexpected() },
            header = { unexpected() }, notificationText = { unexpected() }, hookers = { unexpected() },
            retired = { unexpected() }
        )
        val processNames = RuntimeSettingsGroups.targets + setOf(
            "system_server", "com.miui.securitycenter.power", "com.android.contacts:business", "unscoped",
        )
        for (process in processNames) {
            val dispatcher = SettingsReloadDispatcher(
                post = { _, _ -> unexpected() }, remove = { unexpected() }, failure = { _, _ -> unexpected() }
            )
            RuntimeSettingsParticipants.install(dispatcher, process, process == "system_server", actions)
        }
    }
}
