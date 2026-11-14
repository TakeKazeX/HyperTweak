package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.settings.contract.AodSettingsKeys
import com.takekazex.hypertweak.settings.contract.CarrierRowVisibility
import com.takekazex.hypertweak.settings.contract.IconConfigDecoder
import com.takekazex.hypertweak.settings.contract.IconConfigReload
import com.takekazex.hypertweak.settings.contract.IndependentIconDomain
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconTunerContract

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups as Groups
import com.takekazex.hypertweak.settings.contract.SystemUiLiveSettings
import com.takekazex.hypertweak.hook.runtime.settings.SettingsReloadDispatcher
import org.junit.Assert.*
import org.junit.Test

/**
 * The audit's "no owner" finding: keys that a SystemUI host caches at install time must be
 * owned by a runtime settings group, routed to the process that executes the hook, and delivered
 * in an acknowledged snapshot so a committed change can be applied without a restart.
 */
class SystemUiLiveSettingsRoutingTest {
    private val systemUi = "com.android.systemui"

    @Test fun carrierNameDropdownAndResetRefreshOnlyTheHeaderWithoutHidingSims() {
        val key = IconSettingsKeys.KEY_ICON_CC_CARRIER_HIDING
        val base = IconConfigDecoder.decode { null }
        val expected = setOf(IndependentIconDomain.CONTROL_CENTER_HEADER)
        for (mode in 0..3) {
            val config = IconConfigDecoder.decode { if (it == key) mode else null }
            assertEquals(mode, config.controlCenter.carrierHiding)
            assertEquals(base.network, config.network)
            assertEquals(base.cellular, config.cellular)
            assertEquals(base.slots, config.slots)
            assertEquals(setOf(Groups.ICONS), Groups.affected(key))
            assertEquals(mapOf(key to mode), Groups.subset(systemUi, mapOf(key to mode)))
            assertNotNull(RuntimeSettingsSnapshot.read(1, mapOf(key to mode), setOf(Groups.ICONS)))
            for (next in 0..3) {
                val changed = IconConfigDecoder.decode { if (it == key) next else null }
                assertEquals(if (mode == next) emptySet() else expected, IconConfigReload.domains(config, changed))
            }
        }
    }

    @Test fun twoLineCarriersSuspendNativeHidingAndRestoreTheSavedSelection() {
        fun config(mode: Int, hideDate: Boolean, twoLine: Boolean) = IconConfigDecoder.decode { key -> when (key) {
            IconSettingsKeys.KEY_ICON_CC_CARRIER_HIDING -> mode
            SystemUiSettingsKeys.KEY_CC_HIDE_DATE -> hideDate
            SystemUiSettingsKeys.KEY_CC_CARRIER_TWO_LINE -> twoLine
            else -> null
        } }
        for (mode in 0..3) for (hideDate in listOf(false, true)) for (twoLine in listOf(false, true)) {
            val configured = config(mode, hideDate, twoLine)
            val compact = hideDate && twoLine
            assertEquals(mode, configured.controlCenter.carrierHiding)
            assertEquals(if (compact) 0 else mode, configured.controlCenter.nativeCarrierHiding)
            assertEquals(!compact, CarrierRowVisibility.nativeControlsEnabled(hideDate, twoLine))
            val restored = config(mode, true, false)
            assertEquals(mode, restored.controlCenter.nativeCarrierHiding)
            if (compact) assertTrue(IconConfigReload.domains(configured, restored)!!.contains(IndependentIconDomain.CONTROL_CENTER_HEADER))
            assertEquals(config(0, hideDate, twoLine).network, configured.network)
        }
    }

    @Test fun iconReloadUsesDecodedSectionsIncludingMigratedStyleKeys() {
        fun config(values: Map<String, Any>) = IconConfigDecoder.decode { values[it] }
        val base = config(emptyMap())
        assertEquals(emptySet<IndependentIconDomain>(), IconConfigReload.domains(base, base))
        // Both migrated and current style keys update the same scoped mobile owners.
        val signalOwners = setOf(IndependentIconDomain.POLICY, IndependentIconDomain.CELLULAR,
            IndependentIconDomain.CELLULAR_TYPE, IndependentIconDomain.MOBILE_SIGNAL)
        assertEquals(signalOwners, IconConfigReload.domains(base,
            config(mapOf(IconSettingsKeys.KEY_ICON_STACKED_ENABLED to true))))
        assertEquals(signalOwners, IconConfigReload.domains(base,
            config(mapOf(IconSettingsKeys.KEY_ICON_SIGNAL_STYLE to 1))))
        assertEquals(setOf(IndependentIconDomain.DUO), IconConfigReload.domains(base,
            config(mapOf(IconSettingsKeys.KEY_ICON_DUO_TOP_PERCENT to true))))
        assertEquals(setOf(IndependentIconDomain.POLICY), IconConfigReload.domains(base,
            config(mapOf("icon_tuner_slot_bluetooth" to 4))))
        assertNull(IconConfigReload.domains(base, config(mapOf("icon_tuner_slot_wifi" to 4))))
    }

    @Test fun allStyleTransitionsPreserveWifiBatteryAndSharedHostOwners() {
        for (from in 0..2) for (to in 0..2) {
            fun config(style: Int) = IconConfigDecoder.decode { key ->
                when (key) {
                    IconSettingsKeys.KEY_ICON_SIGNAL_STYLE -> style
                    IconSettingsKeys.KEY_ICON_HIDE_WIFI_ACTIVITY -> true
                    else -> null
                }
            }
            assertNotNull(IconConfigReload.domains(config(from), config(to)))
            val effects = IconConfigReload.domains(config(from), config(to))!!
            assertFalse(effects.contains(IndependentIconDomain.WIFI))
            assertFalse(effects.contains(IndependentIconDomain.CC_BATTERY))
            assertFalse(effects.contains(IndependentIconDomain.CONTROL_CENTER_HEADER))
            assertEquals(from != to && (from == 2 || to == 2), IndependentIconDomain.DUO in effects)
            if (from != to) assertTrue(effects.containsAll(setOf(IndependentIconDomain.POLICY,
                IndependentIconDomain.CELLULAR, IndependentIconDomain.CELLULAR_TYPE, IndependentIconDomain.MOBILE_SIGNAL)))
        }
        val base = IconConfigDecoder.decode { null }
        val svg = IconConfigDecoder.decode { if (it == IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE) 1 else null }
        assertNotNull(IconConfigReload.domains(base, svg))
    }

    @Test fun iconSectionsPreserveIndependentOwnersAndIgnoreOtherGroups() {
        fun config(vararg pairs: Pair<String, Any>) = IconConfigDecoder.decode { mapOf(*pairs)[it] }
        val base = config()
        val header = IndependentIconDomain.CONTROL_CENTER_HEADER
        assertEquals(setOf(IndependentIconDomain.CELLULAR), IconConfigReload.domains(base,
            config(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ACTIVITY to true)))
        assertEquals(setOf(IndependentIconDomain.WIFI), IconConfigReload.domains(base,
            config(IconSettingsKeys.KEY_ICON_WIFI_ACTIVITY_RIGHT to true)))
        assertEquals(setOf(IndependentIconDomain.CC_BATTERY, IndependentIconDomain.DUO), IconConfigReload.domains(base,
            config(SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_STYLE to 1)))
        assertEquals(setOf(header), IconConfigReload.domains(base,
            config(SystemUiSettingsKeys.KEY_CC_PRIVACY_OFFSET to 9)))
        assertEquals(emptySet<IndependentIconDomain>(), IconConfigReload.domains(base,
            config(SystemUiSettingsKeys.KEY_CC_PRIVACY_NAMES to false)))
        assertEquals(setOf(IndependentIconDomain.SAMPLING), IconConfigReload.domains(base,
            config(PlatformSettingsKeys.KEY_STATUSBAR_REGION_SAMPLING to 1)))
        val separate = config(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE to true)
        assertNull(IconConfigReload.domains(separate,
            config(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE to true,
                IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI to true)))
        val reordered = config(IconTunerContract.KEY_POSITION_REORDER to true)
        assertNull(IconConfigReload.domains(reordered, config(
            IconTunerContract.KEY_POSITION_REORDER to true, SystemUiSettingsKeys.KEY_CC_HIDE_DATE to true,
            SystemUiSettingsKeys.KEY_CC_ICON_ROW_OVERRIDES to setOf("wifi"))))
    }

    @Test fun signalStyleReachesIconsAndIslandBudgets() {
        val key = IconSettingsKeys.KEY_ICON_SIGNAL_STYLE
        assertEquals(setOf(Groups.ICONS, Groups.ISLANDS), Groups.affected(key))
        assertEquals(mapOf(key to 2), Groups.subset(systemUi, mapOf(key to 2)))
        assertNotNull(RuntimeSettingsSnapshot.read(1, mapOf(key to 2), setOf(Groups.ICONS, Groups.ISLANDS)))
    }

    @Test fun aodOptionsReachBothAodHosts() {
        val keys = listOf(
            AodSettingsKeys.KEY_AOD_BATTERY_ICON_VISIBLE, AodSettingsKeys.KEY_AOD_DUO_ENABLED,
            AodSettingsKeys.KEY_AOD_BATTERY_PERCENT_VISIBLE, AodSettingsKeys.KEY_AOD_PERCENT_POSITION,
            AodSettingsKeys.KEY_AOD_DUO_CENTER_CONTENT
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
            SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_DATE,
            SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT, SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD,
            SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN, SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH
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
        assertEquals(setOf(Groups.LOCKSCREEN), Groups.affected(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT))
        assertEquals(setOf(Groups.LOCKSCREEN), Groups.affected(SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS))
        assertEquals(setOf(Groups.POWER_SAVE), Groups.affected(PlatformSettingsKeys.KEY_BATTERY_AUTO_POWER_SAVE_THRESHOLD))
        assertEquals(setOf(Groups.POWER_SAVE), Groups.affected(PlatformSettingsKeys.KEY_EXIT_POWER_SAVE_WHEN_CHARGING))
        assertEquals(setOf(Groups.MEDIA_CARD), Groups.affected(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH))
        assertEquals(setOf(Groups.CONTROL_CENTER_PLUGIN), Groups.affected(SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE))
        assertEquals(setOf(Groups.CONTROL_CENTER_PLUGIN), Groups.affected(SystemUiSettingsKeys.KEY_CC_TILE_MESSAGE_DURATION_MS))
        assertEquals(setOf(Groups.GLASS), Groups.affected(SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_TONE))
    }

    @Test fun theClockSecondsKeyIsOwnedAndAppliedByTheHeaderDomain() {
        // The review found this key owned by HEADER while its callback was registered under
        // NOTIFICATION_TEXT, so a seconds-only snapshot never reached it. Ownership and the
        // application callback must live in the same domain.
        for (key in setOf(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS,
            SystemUiSettingsKeys.KEY_STATUS_BAR_CLOCK_SECONDS)) {
            assertEquals(setOf(Groups.HEADER), Groups.affected(key))
            assertTrue(key in SystemUiLiveSettings.HEADER_KEYS)
            assertFalse(key in SystemUiLiveSettings.NOTIFICATION_TEXT_KEYS)
            assertEquals(mapOf(key to true), Groups.subset(systemUi, mapOf(key to true)))
        }
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
        val seconds = setOf(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS)
        // The lock-screen date belongs to KEYGUARD; the bottom-text key is LOCKSCREEN's own.
        val groups = Groups.affected(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS) +
            Groups.affected(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT)
        dispatcher.request(groups, seconds + SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT)
        queued.removeLast().run()
        assertEquals(setOf(Groups.HEADER, Groups.LOCKSCREEN), applied.keys)
        assertTrue(applied.getValue(Groups.HEADER).contains(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS))
        assertTrue(applied.getValue(Groups.LOCKSCREEN).contains(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT))
        // Each domain sees the whole committed batch; the filter is the participant's own
        // key list, so the seconds key is not attributed to the lock-screen domain by ownership.
        assertFalse(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS in SystemUiLiveSettings.LOCKSCREEN_KEYS)
    }

    @Test fun rapidChangesAndResetKeepOneAppliedKeySetPerDomain() {
        val applied = ArrayList<Set<String>>()
        val queued = ArrayList<Runnable>()
        val dispatcher = SettingsReloadDispatcher({ task, _ -> queued += task }, {}, { _, _ -> })
        dispatcher.register(Groups.AOD) { keys -> applied += keys; true }
        dispatcher.request(setOf(Groups.AOD), setOf(AodSettingsKeys.KEY_AOD_PERCENT_POSITION))
        dispatcher.request(setOf(Groups.AOD), setOf(AodSettingsKeys.KEY_AOD_DUO_ENABLED))
        queued.removeLast().run()
        // Coalesced into one drain that carries both committed keys.
        assertEquals(listOf(setOf(AodSettingsKeys.KEY_AOD_PERCENT_POSITION, AodSettingsKeys.KEY_AOD_DUO_ENABLED)), applied)
        dispatcher.resume()
        // A resume applies the whole domain so a replacement generation cannot keep a stale value.
        assertEquals(1, applied.size)
    }

    @Test fun changedKeysReportsOnlyCommittedValueChanges() {
        val ledger = RuntimeSettingsLedger()
        val first = RuntimeSettingsSnapshot.read(1, mapOf(
            AodSettingsKeys.KEY_AOD_DUO_ENABLED to false,
            SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_DATE to false
        ), setOf(Groups.AOD, Groups.KEYGUARD))!!
        assertTrue(ledger.accept(first))
        val next = RuntimeSettingsSnapshot.read(2, mapOf(
            AodSettingsKeys.KEY_AOD_DUO_ENABLED to true,
            SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_DATE to false
        ), setOf(Groups.AOD, Groups.KEYGUARD))!!
        assertEquals(setOf(AodSettingsKeys.KEY_AOD_DUO_ENABLED), ledger.changedKeys(next))
        // A reset removes the keys, which is also a committed change.
        val reset = RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>(), setOf(Groups.AOD, Groups.KEYGUARD))!!
        assertEquals(setOf(AodSettingsKeys.KEY_AOD_DUO_ENABLED), ledger.changedKeys(reset).intersect(setOf(AodSettingsKeys.KEY_AOD_DUO_ENABLED)))
    }

    @Test fun fullscreenUnlockReachesAllThreeConsumersAndResetRemovesIt() {
        val key = AodSettingsKeys.KEY_AOD_FULLSCREEN
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
        assertEquals(setOf(Groups.FREEFORM_BLUR), Groups.affected(PlatformSettingsKeys.KEY_FREEFORM_BLUR_TRANSITION))
        assertTrue(Groups.subset("com.android.settings", mapOf(PlatformSettingsKeys.KEY_FREEFORM_BLUR_TRANSITION to true)).isEmpty())
    }

    @Test fun addedDefaultOnlyDomainIsNotNarrowedByAnotherDomainsValueDiff() {
        val ledger = RuntimeSettingsLedger()
        // An icon-only key; the Duo switch also routes to islands.
        ledger.accept(RuntimeSettingsSnapshot.read(1, mapOf("icon_hide_cellular_type" to true), setOf(Groups.ICONS))!!)
        val next = RuntimeSettingsSnapshot.read(2, mapOf("icon_hide_cellular_type" to false),
            setOf(Groups.ICONS, Groups.HEADER))!!
        assertEquals(setOf(Groups.ICONS, Groups.HEADER), ledger.changedGroups(next))
        // HEADER has only omitted defaults; the icon key must not make its participant skip them.
        assertTrue(ledger.changedKeys(next).isEmpty())
    }

    @Test fun aodSnapshotNeverCarriesAnotherDomain() {
        val values = mapOf<String, Any>(
            AodSettingsKeys.KEY_AOD_DUO_ENABLED to true,
            SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_DATE to true,
            SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_HIDE_TIME to true,
            "icon_duo_enabled" to true
        )
        // A snapshot declares its completed groups; keys of other destinations are not published
        // through this subset, and the reader keeps only the keys the declared groups own.
        val aod = Groups.subset(systemUi, values)
        assertEquals(
            setOf(AodSettingsKeys.KEY_AOD_DUO_ENABLED, SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_DATE,
                SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_HIDE_TIME, "icon_duo_enabled"),
            aod.keys
        )
        val snapshot = RuntimeSettingsSnapshot.read(7, aod, setOf(Groups.AOD))!!
        assertFalse(snapshot.values.containsKey(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_DATE))
        assertFalse(snapshot.values.containsKey(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_HIDE_TIME))
        assertEquals(mapOf(AodSettingsKeys.KEY_AOD_DUO_ENABLED to true), snapshot.values)
        assertEquals(setOf(Groups.AOD), snapshot.groups)
    }

    @Test fun resetAndRapidChangesReachEachDomainOnce() {
        val ledger = RuntimeSettingsLedger()
        val first = RuntimeSettingsSnapshot.read(1,
            mapOf(AodSettingsKeys.KEY_AOD_PERCENT_POSITION to 0, SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR to true),
            setOf(Groups.AOD, Groups.KEYGUARD))!!
        assertTrue(ledger.accept(first))
        // A newer snapshot for the same domain is applied; an older one is rejected.
        val next = RuntimeSettingsSnapshot.read(2,
            mapOf(AodSettingsKeys.KEY_AOD_PERCENT_POSITION to 2, SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR to true),
            setOf(Groups.AOD, Groups.KEYGUARD))!!
        assertEquals(setOf(Groups.AOD), ledger.changedGroups(next))
        assertTrue(ledger.accept(next))
        assertFalse(ledger.accept(first))
        val reset = RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>(), setOf(Groups.AOD, Groups.KEYGUARD))!!
        assertEquals(setOf(Groups.AOD, Groups.KEYGUARD), ledger.changedGroups(reset))
        assertTrue(ledger.accept(reset))
        assertNull(ledger.value(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR))
    }

    @Test fun aGenerationWithoutTheNewDomainsAppliesTheirDefaultsOnce() {
        // An upgrade from a version that had no AOD/keyguard state must still publish an empty
        // domain, so cached native values from that generation cannot survive the migration.
        val legacy = RuntimeSettingsLedger().also { it.restore(arrayOf<Any>(1L, hashMapOf("icon_duo_enabled" to true))) }
        assertFalse(legacy.owns(AodSettingsKeys.KEY_AOD_DUO_ENABLED))
        val current = RuntimeSettingsSnapshot.read(2L, mapOf("icon_duo_enabled" to true))!!
        assertTrue(legacy.changedGroups(current).containsAll(setOf(Groups.AOD, Groups.KEYGUARD)))
    }
}
