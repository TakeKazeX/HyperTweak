package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.settings.contract.IconSettingsKeys

import android.content.SharedPreferences
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PreferencesSignalSvgStyleTest {
    private val key = IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE
    private val legacy = "icon_tuner_stacked_icon_svg_stacked"

    private fun isolated(values: Map<String, Any> = emptyMap(), block: () -> Unit) {
        val fields = listOf("remotePrefs", "localSourcePrefs", "localCachePrefs")
            .map { Preferences::class.java.getDeclaredField(it).apply { isAccessible = true } }
        val previous = fields.map { it.get(Preferences) }
        val ledger = RuntimeSettingsStore::class.java.getDeclaredField("ledger").apply { isAccessible = true }.get(RuntimeSettingsStore)
        val snapshot = RuntimeSettingsLedger::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
        val oldSnapshot = snapshot.get(ledger)
        val target = RuntimeSettingsStore::class.java.getDeclaredField("target").apply { isAccessible = true }
        val oldTarget = target.get(RuntimeSettingsStore)
        val backend = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            val name = args?.firstOrNull() as? String
            when (method.name) {
                "contains" -> values.containsKey(name)
                "getInt" -> values[name]?.let { it as Int } ?: args!![1]
                "getLong" -> values[name]?.let { it as Long } ?: args!![1]
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences
        try {
            snapshot.set(ledger, null)
            target.set(RuntimeSettingsStore, null)
            fields[0].set(Preferences, backend)
            fields[1].set(Preferences, null)
            fields[2].set(Preferences, null)
            Preferences.invalidateRuntimeReadCache()
            block()
        } finally {
            fields.zip(previous).forEach { (field, value) -> field.set(Preferences, value) }
            snapshot.set(ledger, oldSnapshot)
            target.set(RuntimeSettingsStore, oldTarget)
            Preferences.invalidateRuntimeReadCache()
        }
    }

    @Test fun legacyAndExplicitNewValuesAreReadThroughPreferences() {
        isolated(mapOf(legacy to 3)) { assertEquals(1, Preferences.iconStackedSignalStyle()) }
        isolated(mapOf(legacy to 3, key to 0)) { assertEquals(0, Preferences.iconStackedSignalStyle()) }
        isolated(mapOf(legacy to 0, key to 1)) { assertEquals(1, Preferences.iconStackedSignalStyle()) }
        isolated(mapOf(legacy to 3, key to 99)) { assertEquals(0, Preferences.iconStackedSignalStyle()) }
        isolated(mapOf(legacy to 3, key to "invalid")) { assertEquals(0, Preferences.iconStackedSignalStyle()) }
    }

    @Test fun committedSnapshotWinsOverContradictoryRemoteAndResetCannotResurrectOldKeys() {
        isolated(mapOf(key to 0, legacy to 3)) {
            fun accept(revision: Long, values: Map<String, Any>) {
                assertTrue(RuntimeSettingsStore.accept(RuntimeSettingsSnapshot.read(revision, values, setOf(RuntimeSettingsGroups.ICONS))!!))
            }
            accept(1, mapOf(key to 1))
            assertEquals(1, Preferences.iconStackedSignalStyle())
            accept(2, mapOf(legacy to 3))
            assertEquals(1, Preferences.iconStackedSignalStyle())
            accept(3, mapOf(key to 0, legacy to 3))
            assertEquals(0, Preferences.iconStackedSignalStyle())
            accept(4, emptyMap())
            assertEquals(0, Preferences.iconStackedSignalStyle())
            accept(5, mapOf(key to "invalid", legacy to 3))
            assertEquals(0, Preferences.iconStackedSignalStyle())
        }
    }

    @Test fun newKeyRoutesAndDiffsAsIconsAndSurvivesSavedSnapshot() {
        assertEquals(setOf(RuntimeSettingsGroups.ICONS), RuntimeSettingsGroups.affected(key))
        assertTrue(RuntimeSettingsGroups.owns("com.android.systemui", key))
        val first = RuntimeSettingsSnapshot.read(1, mapOf(key to 1), setOf(RuntimeSettingsGroups.ICONS))!!
        val ledger = RuntimeSettingsLedger()
        assertTrue(ledger.accept(first))
        val restored = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
        assertEquals(1, restored.value(key))
        val reset = RuntimeSettingsSnapshot.read(2, emptyMap<String, Any>(), setOf(RuntimeSettingsGroups.ICONS))!!
        assertEquals(setOf(key), restored.changedKeys(reset))
        assertEquals(setOf(RuntimeSettingsGroups.ICONS), restored.changedGroups(reset))
        assertTrue(restored.accept(reset))
        assertNull(restored.value(key))
        assertTrue(restored.owns(key))
    }

    @Test fun pageEpochReadsTheActualResetIdentity() {
        isolated(mapOf("prefs_epoch" to 12L)) { assertEquals(12L, Preferences.settingsEpochForUi()) }
        isolated { assertEquals(0L, Preferences.settingsEpochForUi()) }
    }
}
