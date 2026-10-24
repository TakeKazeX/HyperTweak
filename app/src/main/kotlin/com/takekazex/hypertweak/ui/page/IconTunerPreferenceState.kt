package com.takekazex.hypertweak.ui.page

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
