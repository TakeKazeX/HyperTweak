package com.takekazex.hypertweak.hook.base

import java.lang.ref.WeakReference
import java.lang.reflect.Field

/** Native constructor values survive repeated toggles and generation retirement. */
internal class BooleanFieldOverrides {
    private class Entry(val owner: WeakReference<Any>, val fields: Map<Field, Boolean>, val native: Map<Field, Boolean>)
    private val entries = mutableListOf<Entry>()

    @Synchronized fun capture(owner: Any, fields: List<Field>) = capture(owner, fields.associateWith { true })

    @Synchronized fun capture(owner: Any, fields: Map<Field, Boolean>) {
        entries.removeAll { it.owner.get() == null }
        if (entries.none { it.owner.get() === owner }) {
            entries += Entry(WeakReference(owner), fields, fields.keys.associateWith { it.getBoolean(owner) })
        }
    }

    @Synchronized fun apply(enabled: (Any) -> Boolean) {
        entries.removeAll { it.owner.get() == null }
        entries.forEach { entry ->
            val owner = entry.owner.get() ?: return@forEach
            val override = enabled(owner)
            entry.fields.forEach { (field, value) ->
                field.setBoolean(owner, if (override) value else entry.native.getValue(field))
            }
        }
    }

    @Synchronized fun restore() = apply { false }
    @Synchronized fun clear() { entries.clear() }
}
