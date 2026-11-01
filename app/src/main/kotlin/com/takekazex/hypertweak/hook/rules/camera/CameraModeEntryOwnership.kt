package com.takekazex.hypertweak.hook.rules.camera

/** Removes only entries actually inserted by this generation, preserving native/other owners. */
internal class CameraModeEntryOwnership {
    enum class Feature { MASTER_LIVE, LEGENDARY, EXTRA }
    private data class Entry(val value: Any, val feature: Feature)
    private val entries = mutableMapOf<Int, Entry>()

    fun added(id: Int, value: Any, feature: Feature) { entries[id] = Entry(value, feature) }

    fun restore(enabled: Set<Feature>, read: (Int) -> Any?, remove: (Int) -> Unit) {
        val retired = entries.filterValues { it.feature !in enabled }
        retired.forEach { (id, entry) ->
            if (read(id) === entry.value) remove(id)
            entries.remove(id)
        }
    }
}
