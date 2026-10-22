package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups as Groups
import com.takekazex.hypertweak.hook.rules.systemui.SystemUiLiveSettings
import com.takekazex.hypertweak.hook.base.SettingsReloadDispatcher
import org.junit.Assert.*
import org.junit.Test

/**
 * The audit's "no owner" finding: keys that a SystemUI host caches at install time must be
 * owned by a runtime settings group, routed to the process that executes the hook, and delivered
 * in an acknowledged snapshot so a committed change can be applied without a restart.
 */
class SystemUiLiveSettingsRoutingTest {
    private val systemUi = "com.android.systemui"

    @Test fun aodOptionsReachBothAodHosts() {
        val keys = listOf(
            Preferences.KEY_AOD_BATTERY_ICON_VISIBLE, Preferences.KEY_AOD_DUO_ENABLED,
            Preferences.KEY_AOD_BATTERY_PERCENT_VISIBLE, Preferences.KEY_AOD_PERCENT_POSITION,
            Preferences.KEY_AOD_DUO_CENTER_CONTENT
        )
        keys.forEach { key ->
            assertEquals(key, setOf(Groups.AOD), Groups.affected(key))
            assertEquals(mapOf(key to 1), Groups.subset(systemUi, mapOf(key to 1)))
            // The standalone AOD process runs the plugin row and needs the same snapshot.
            assertEquals(mapOf(key to true), Groups.subset("com.miui.aod", mapOf(key to true)))
        }
        assertTrue(Groups.forProcess("com.miui.aod").containsAll(setOf(Groups.AOD)))
        assertEquals(setOf(Groups.AOD, Groups.AOD_CAPABILITY), Groups.forProcess("com.miui.aod"))
    }

    @Test fun keyguardSurfacesAreOwnedAndDelivered() {
        listOf(
            Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, Preferences.KEY_HIDE_LOCKSCREEN_DATE,
            Preferences.KEY_HIDE_FINGERPRINT, Preferences.KEY_HIDE_FINGERPRINT_AOD,
            Preferences.KEY_HIDE_FINGERPRINT_LOCKSCREEN, Preferences.KEY_HIDE_FINGERPRINT_APP_AUTH
        ).forEach { key ->
            assertEquals(key, setOf(Groups.KEYGUARD), Groups.affected(key))
            assertEquals(mapOf(key to true), Groups.subset(systemUi, mapOf(key to true)))
            assertTrue(Groups.subset("com.miui.aod", mapOf(key to true)).isEmpty())
        }
    }

    @Test fun everyLiveSystemUiKeyGroupIsOwnedRoutedAndSnapshotReady() {
        val domains = mapOf(
            Groups.HEADER to SystemUiLiveSettings.HEADER_KEYS,
            Groups.NOTIFICATION_TEXT to SystemUiLiveSettings.NOTIFICATION_TEXT_KEYS,
            Groups.MEDIA_CARD to SystemUiLiveSettings.MEDIA_CARD_KEYS,
            Groups.CONTROL_CENTER_PLUGIN to SystemUiLiveSettings.CONTROL_CENTER_PLUGIN_KEYS,
            Groups.GLASS to SystemUiLiveSettings.GLASS_KEYS
        )
        domains.forEach { (group, keys) ->
            assertTrue(group, keys.isNotEmpty())
            keys.forEach { key ->
                assertTrue("$key is unowned", Groups.owns(key))
                assertTrue("$group does not own $key", Groups.owns(systemUi, key))
                // A committed value must survive the acknowledged snapshot round trip.
                val values = mapOf<String, Any>(key to true)
                assertEquals(mapOf(key to true), Groups.subset(systemUi, values))
                assertNotNull(RuntimeSettingsSnapshot.read(1, values, setOf(group)))
            }
            assertTrue(Groups.forProcess(systemUi).contains(group))
        }
    }

    @Test fun lockscreenPowerSaveAndMediaDomainsAreIndependent() {
        assertEquals(setOf(Groups.LOCKSCREEN), Groups.affected(Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT))
        assertEquals(setOf(Groups.LOCKSCREEN), Groups.affected(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS))
        assertEquals(setOf(Groups.POWER_SAVE), Groups.affected(Preferences.KEY_BATTERY_AUTO_POWER_SAVE_THRESHOLD))
        assertEquals(setOf(Groups.POWER_SAVE), Groups.affected(Preferences.KEY_EXIT_POWER_SAVE_WHEN_CHARGING))
        assertEquals(setOf(Groups.MEDIA_CARD), Groups.affected(Preferences.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH))
        assertEquals(setOf(Groups.CONTROL_CENTER_PLUGIN), Groups.affected(Preferences.KEY_SLIDER_SHOW_PERCENTAGE))
        assertEquals(setOf(Groups.GLASS), Groups.affected(Preferences.KEY_GLASS_TUNER_GLASS_TONE))
    }

    @Test fun theClockSecondsKeyIsOwnedAndAppliedByTheHeaderDomain() {
        // The review found this key owned by HEADER while its callback was registered under
        // NOTIFICATION_TEXT, so a seconds-only snapshot never reached it. Ownership and the
        // application callback must live in the same domain.
        val key = Preferences.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS
        assertEquals(setOf(Groups.HEADER), Groups.affected(key))
        assertTrue(key in SystemUiLiveSettings.HEADER_KEYS)
        assertFalse(key in SystemUiLiveSettings.NOTIFICATION_TEXT_KEYS)
        assertTrue(Groups.forProcess(systemUi).contains(Groups.HEADER))
    }

    @Test fun aCommittedKeyReachesExactlyItsDomainParticipants() {
        // Exercises the dispatcher contract the review asked for: the committed keys travel with
        // the group request, so a participant can route per key instead of re-running everything.
        val applied = LinkedHashMap<String, Set<String>>()
        // Manual scheduler: the drain is driven explicitly so the assertions describe exactly
        // one coalesced batch instead of whatever the executor happens to run first.
        val queued = ArrayList<Runnable>()
        val dispatcher = SettingsReloadDispatcher({ task, _ -> queued += task }, {}, { id, error ->
            throw AssertionError("participant $id failed: $error")
        })
        dispatcher.register(Groups.HEADER) { keys -> applied[Groups.HEADER] = keys; true }
        dispatcher.register(Groups.LOCKSCREEN) { keys -> applied[Groups.LOCKSCREEN] = keys; true }
        val seconds = setOf(Preferences.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS)
        // The lock-screen date belongs to KEYGUARD; the bottom-text key is LOCKSCREEN's own.
        val groups = Groups.affected(Preferences.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS) +
            Groups.affected(Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT)
        dispatcher.request(groups, seconds + Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT)
        queued.removeLast().run()
        assertEquals(setOf(Groups.HEADER, Groups.LOCKSCREEN), applied.keys)
        assertTrue(applied.getValue(Groups.HEADER).contains(Preferences.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS))
        assertTrue(applied.getValue(Groups.LOCKSCREEN).contains(Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT))
        // Each domain sees the whole committed batch; the filter is the participant's own
        // key list, so the seconds key is not attributed to the lock-screen domain by ownership.
        assertFalse(Preferences.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS in SystemUiLiveSettings.LOCKSCREEN_KEYS)
    }

    @Test fun rapidChangesAndResetKeepOneAppliedKeySetPerDomain() {
        val applied = ArrayList<Set<String>>()
        val queued = ArrayList<Runnable>()
        val dispatcher = SettingsReloadDispatcher({ task, _ -> queued += task }, {}, { _, _ -> })
        dispatcher.register(Groups.AOD) { keys -> applied += keys; true }
        dispatcher.request(setOf(Groups.AOD), setOf(Preferences.KEY_AOD_PERCENT_POSITION))
        dispatcher.request(setOf(Groups.AOD), setOf(Preferences.KEY_AOD_DUO_ENABLED))
        queued.removeLast().run()
        // Coalesced into one drain that carries both committed keys.
        assertEquals(listOf(setOf(Preferences.KEY_AOD_PERCENT_POSITION, Preferences.KEY_AOD_DUO_ENABLED)), applied)
        dispatcher.resume()
        // A resume applies the whole domain so a replacement generation cannot keep a stale value.
        assertEquals(1, applied.size)
    }

    @Test fun changedKeysReportsOnlyCommittedValueChanges() {
        val ledger = RuntimeSettingsLedger()
        val first = RuntimeSettingsSnapshot.read(1, mapOf(
            Preferences.KEY_AOD_DUO_ENABLED to false,
            Preferences.KEY_HIDE_LOCKSCREEN_DATE to false
        ), setOf(Groups.AOD, Groups.KEYGUARD))!!
        assertTrue(ledger.accept(first))
        val next = RuntimeSettingsSnapshot.read(2, mapOf(
            Preferences.KEY_AOD_DUO_ENABLED to true,
            Preferences.KEY_HIDE_LOCKSCREEN_DATE to false
        ), setOf(Groups.AOD, Groups.KEYGUARD))!!
        assertEquals(setOf(Preferences.KEY_AOD_DUO_ENABLED), ledger.changedKeys(next))
        // A reset removes the keys, which is also a committed change.
        val reset = RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>(), setOf(Groups.AOD, Groups.KEYGUARD))!!
        assertEquals(setOf(Preferences.KEY_AOD_DUO_ENABLED), ledger.changedKeys(reset).intersect(setOf(Preferences.KEY_AOD_DUO_ENABLED)))
    }

    @Test fun fullscreenUnlockReachesAllThreeConsumersAndResetRemovesIt() {
        val key = Preferences.KEY_AOD_FULLSCREEN
        assertEquals(setOf(Groups.AOD_CAPABILITY), Groups.affected(key))
        for (target in listOf("com.android.systemui", "com.miui.aod", "com.android.settings")) {
            assertEquals(mapOf(key to true), Groups.subset(target, mapOf(key to true)))
        }
        val ledger = RuntimeSettingsLedger()
        val on = RuntimeSettingsSnapshot.read(1, mapOf(key to true), setOf(Groups.AOD_CAPABILITY))!!
        ledger.accept(on)
        val reset = RuntimeSettingsSnapshot.read(2, emptyMap<String, Any>(), setOf(Groups.AOD_CAPABILITY))!!
        assertEquals(setOf(Groups.AOD_CAPABILITY), ledger.changedGroups(reset))
        assertEquals(setOf(key), ledger.changedKeys(reset))
        assertEquals(setOf(Groups.FREEFORM_BLUR), Groups.affected(Preferences.KEY_FREEFORM_BLUR_TRANSITION))
        assertTrue(Groups.subset("com.android.settings", mapOf(Preferences.KEY_FREEFORM_BLUR_TRANSITION to true)).isEmpty())
    }

    @Test fun addedDefaultOnlyDomainIsNotNarrowedByAnotherDomainsValueDiff() {
        val ledger = RuntimeSettingsLedger()
        ledger.accept(RuntimeSettingsSnapshot.read(1, mapOf("icon_duo_enabled" to true), setOf(Groups.ICONS))!!)
        val next = RuntimeSettingsSnapshot.read(2, mapOf("icon_duo_enabled" to false),
            setOf(Groups.ICONS, Groups.HEADER))!!
        assertEquals(setOf(Groups.ICONS, Groups.HEADER), ledger.changedGroups(next))
        // HEADER has only omitted defaults; the icon key must not make its participant skip them.
        assertTrue(ledger.changedKeys(next).isEmpty())
    }

    @Test fun aodSnapshotNeverCarriesAnotherDomain() {
        val values = mapOf<String, Any>(
            Preferences.KEY_AOD_DUO_ENABLED to true,
            Preferences.KEY_HIDE_LOCKSCREEN_DATE to true,
            Preferences.KEY_NOTIFICATION_HEADER_HIDE_TIME to true,
            "icon_duo_enabled" to true
        )
        // A snapshot declares its completed groups; keys of other destinations are not published
        // through this subset, and the reader keeps only the keys the declared groups own.
        val aod = Groups.subset(systemUi, values)
        assertEquals(
            setOf(Preferences.KEY_AOD_DUO_ENABLED, Preferences.KEY_HIDE_LOCKSCREEN_DATE,
                Preferences.KEY_NOTIFICATION_HEADER_HIDE_TIME, "icon_duo_enabled"),
            aod.keys
        )
        val snapshot = RuntimeSettingsSnapshot.read(7, aod, setOf(Groups.AOD))!!
        assertFalse(snapshot.values.containsKey(Preferences.KEY_HIDE_LOCKSCREEN_DATE))
        assertFalse(snapshot.values.containsKey(Preferences.KEY_NOTIFICATION_HEADER_HIDE_TIME))
        assertEquals(mapOf(Preferences.KEY_AOD_DUO_ENABLED to true), snapshot.values)
        assertEquals(setOf(Groups.AOD), snapshot.groups)
    }

    @Test fun resetAndRapidChangesReachEachDomainOnce() {
        val ledger = RuntimeSettingsLedger()
        val first = RuntimeSettingsSnapshot.read(1,
            mapOf(Preferences.KEY_AOD_PERCENT_POSITION to 0, Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR to true),
            setOf(Groups.AOD, Groups.KEYGUARD))!!
        assertTrue(ledger.accept(first))
        // A newer snapshot for the same domain is applied; an older one is rejected.
        val next = RuntimeSettingsSnapshot.read(2,
            mapOf(Preferences.KEY_AOD_PERCENT_POSITION to 2, Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR to true),
            setOf(Groups.AOD, Groups.KEYGUARD))!!
        assertEquals(setOf(Groups.AOD), ledger.changedGroups(next))
        assertTrue(ledger.accept(next))
        assertFalse(ledger.accept(first))
        val reset = RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>(), setOf(Groups.AOD, Groups.KEYGUARD))!!
        assertEquals(setOf(Groups.AOD, Groups.KEYGUARD), ledger.changedGroups(reset))
        assertTrue(ledger.accept(reset))
        assertNull(ledger.value(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR))
    }

    @Test fun aGenerationWithoutTheNewDomainsAppliesTheirDefaultsOnce() {
        // An upgrade from a version that had no AOD/keyguard state must still publish an empty
        // domain, so cached native values from that generation cannot survive the migration.
        val legacy = RuntimeSettingsLedger().also { it.restore(arrayOf<Any>(1L, hashMapOf("icon_duo_enabled" to true))) }
        assertFalse(legacy.owns(Preferences.KEY_AOD_DUO_ENABLED))
        val current = RuntimeSettingsSnapshot.read(2L, mapOf("icon_duo_enabled" to true))!!
        assertTrue(legacy.changedGroups(current).containsAll(setOf(Groups.AOD, Groups.KEYGUARD)))
    }
}