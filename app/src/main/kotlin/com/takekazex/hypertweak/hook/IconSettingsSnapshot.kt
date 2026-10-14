package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSettingsPreferencePolicy

/** Complete committed icon subset; omitted keys mean defaults, not cached remote values. */
internal data class IconSettingsSnapshot(val revision: Long, val values: Map<String, Any>) {
    companion object {
        fun owns(key: String): Boolean = key != "prefs_epoch" && IconSettingsPreferencePolicy.affectsIcons(key)
        fun read(revision: Long, source: Map<String, *>): IconSettingsSnapshot? {
            if (revision <= 0) return null
            val values = LinkedHashMap<String, Any>()
            var size = 0
            for ((key, raw) in source) {
                if (!owns(key)) continue
                val value = when (raw) {
                    is Boolean, is Int, is Long -> raw
                    is Float -> raw.takeIf { it.isFinite() } ?: return null
                    is String -> raw.takeIf { it.length <= 131072 } ?: return null
                    is Set<*> -> raw.takeIf { it.size <= 1024 && it.all { v -> v is String } }
                        ?.map { it as String }?.toSet() ?: return null
                    else -> return null
                }
                size += key.length + value.toString().length
                if (values.size >= 512 || size > 262144) return null
                values[key] = value
            }
            return IconSettingsSnapshot(revision, values.toMap())
        }
    }
    fun mutate(key: String, value: Any, revision: Long): IconSettingsSnapshot? =
        if (!owns(key) || revision <= this.revision) null else read(revision, values + (key to value))
}

internal class IconSettingsLedger {
    @Volatile private var snapshot: IconSettingsSnapshot? = null
    fun owns(key: String): Boolean = snapshot != null && IconSettingsSnapshot.owns(key)
    fun value(key: String): Any? = snapshot?.values?.get(key)
    @Synchronized fun accept(next: IconSettingsSnapshot): Boolean {
        if (snapshot?.revision?.let { next.revision <= it } == true) return false
        snapshot = next
        return true
    }
    fun save(): Array<Any>? = snapshot?.let { arrayOf(it.revision, HashMap(it.values)) }
    fun restore(saved: Any?) {
        val data = saved as? Array<*> ?: return
        val revision = data.getOrNull(0) as? Long ?: return
        val raw = data.getOrNull(1) as? Map<*, *> ?: return
        if (raw.keys.any { it !is String }) return
        @Suppress("UNCHECKED_CAST")
        IconSettingsSnapshot.read(revision, raw as Map<String, *>)?.let(::accept)
    }
}

internal object IconSettingsRuntime {
    private val ledger = IconSettingsLedger()
    fun owns(key: String): Boolean = ledger.owns(key)
    fun value(key: String): Any? = ledger.value(key)
    fun accept(next: IconSettingsSnapshot): Boolean = ledger.accept(next).also {
        if (it) Preferences.invalidateRuntimeReadCache()
    }
    fun save(): Array<Any>? = ledger.save()
    fun restore(saved: Any?) {
        ledger.restore(saved)
        Preferences.invalidateRuntimeReadCache()
    }
}
