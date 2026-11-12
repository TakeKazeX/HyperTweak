package com.takekazex.hypertweak.ui.page.systemui.icon

import com.takekazex.hypertweak.settings.contract.AppSettingsStore
import com.takekazex.hypertweak.settings.contract.IconConfig
import com.takekazex.hypertweak.settings.contract.IconConfigDecoder
import com.takekazex.hypertweak.settings.contract.IconConfigMigration

internal fun decodeIconSettings(store: AppSettingsStore, edits: Map<String, Any> = emptyMap()): IconConfig =
    IconConfigDecoder.decode { key ->
        val local = edits[key]
        if (local != null) {
            if (local is List<*>) local.filterIsInstance<String>().toSet() else local
        } else if (!store.contains(key)) {
            null
        } else when (IconConfigDecoder.defaultValue(key) ?: IconConfigMigration.legacyType(key)) {
            is Boolean -> store.getBoolean(key)
            is Int -> store.getInt(key)
            is Float -> store.getFloat(key)
            is String -> store.getString(key)
            is Set<*> -> store.getStringSet(key)
            else -> null
        }
    }

/** Saveable UI metadata only; it must never be published as a module preference. */
internal object IconTunerPreferenceState {
    private const val EPOCH = "__ht_icon_prefs_epoch"

    fun reconcileEpoch(values: MutableMap<String, Any>, epoch: Long?) {
        if (epoch == null || values[EPOCH] == epoch) return
        values.clear()
        values[EPOCH] = epoch
    }

    fun save(values: Map<String, Any>): ArrayList<Any> = ArrayList<Any>(values.size * 2).apply {
        values.forEach { (key, value) -> add(key); add(value) }
    }

    fun restore(payload: List<Any>): Map<String, Any> = buildMap {
        payload.chunked(2).forEach { entry ->
            if (entry.size == 2 && entry[0] is String) put(entry[0] as String, entry[1])
        }
    }
}
