package com.takekazex.hypertweak.hook.base

import java.lang.ref.WeakReference
import java.lang.reflect.Field

/** Native constructor values survive repeated toggles and generation retirement. */
internal class BooleanFieldOverrides {
    private class Entry(val owner: WeakReference<Any>, val fields: List<Field>, val native: BooleanArray)
    private val entries = mutableListOf<Entry>()

    @Synchronized fun capture(owner: Any, fields: List<Field>) {
        entries.removeAll { it.owner.get() == null }
        if (entries.none { it.owner.get() === owner }) {
            entries += Entry(WeakReference(owner), fields, BooleanArray(fields.size) { fields[it].getBoolean(owner) })
        }
    }

    @Synchronized fun apply(enabled: (Any) -> Boolean) {
        entries.removeAll { it.owner.get() == null }
        entries.forEach { entry ->
            val owner = entry.owner.get() ?: return@forEach
            val override = enabled(owner)
            entry.fields.forEachIndexed { index, field -> field.setBoolean(owner, override || entry.native[index]) }
        }
    }

    @Synchronized fun restore() = apply { false }
    @Synchronized fun clear() { entries.clear() }
}
