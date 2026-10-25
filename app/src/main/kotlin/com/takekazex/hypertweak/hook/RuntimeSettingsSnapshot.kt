package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups

/** Complete committed registered settings subset; omitted keys mean defaults, not cached remote values. */
internal data class RuntimeSettingsSnapshot(val revision: Long, val values: Map<String, Any>,
    val groups: Set<String> = RuntimeSettingsGroups.affected(null)) {
    companion object {
        fun owns(key: String): Boolean = RuntimeSettingsGroups.owns(key)
        fun read(revision: Long, source: Map<String, *>, groups: Set<String> = RuntimeSettingsGroups.affected(null)): RuntimeSettingsSnapshot? {
            if (revision <= 0 || groups.isEmpty() || !RuntimeSettingsGroups.affected(null).containsAll(groups)) return null
            val values = LinkedHashMap<String, Any>()
            var size = 0
            for ((key, raw) in source) {
                if (RuntimeSettingsGroups.owners(key).none(groups::contains)) continue
                if (key in RuntimeSettingsGroups.strictBooleanKeys && raw !is Boolean) return null
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
            if (RuntimeSettingsGroups.SCREENSHOT in groups &&
                com.takekazex.hypertweak.settings.contract.ScreenshotSettingsSnapshot.read(revision, values) == null) return null
            if (RuntimeSettingsGroups.NATIVE in groups && NativeRuleStateSnapshot.read { values } == null) return null
            if (RuntimeSettingsGroups.BACK in groups && BackGestureSettingsSnapshot.read(values) == null) return null
            val predictionLimit = values[PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_PREDICT_LIMIT]
            if (predictionLimit != null && (predictionLimit !is Int || predictionLimit !in 0..15)) return null
            val dock = values[com.takekazex.hypertweak.settings.contract.DockConfig.KEY]
            if (dock != null && (dock !is String || com.takekazex.hypertweak.settings.contract.DockConfig.decode(dock) == null)) return null
            return RuntimeSettingsSnapshot(revision, values.toMap(), groups.toSet())
        }
    }
    fun mutate(key: String, value: Any, revision: Long): RuntimeSettingsSnapshot? =
        if (RuntimeSettingsGroups.owners(key).none(groups::contains) || revision <= this.revision) null else read(revision, values + (key to value), groups)
}

internal class RuntimeSettingsLedger {
    @Volatile private var snapshot: RuntimeSettingsSnapshot? = null
    fun owns(key: String): Boolean = snapshot?.groups?.let { groups -> RuntimeSettingsGroups.owners(key).any(groups::contains) } == true
    fun latest(): RuntimeSettingsSnapshot? = snapshot
    fun value(key: String): Any? = snapshot?.values?.get(key)
    fun changedGroups(next: RuntimeSettingsSnapshot): Set<String> = snapshot?.let {
        RuntimeSettingsGroups.changed(it.values, next.values) + (next.groups - it.groups)
    } ?: next.groups

    /**
     * The committed keys whose values actually changed (including removals). Empty means a
     * full application: new scopes can own default/absent keys that have no value diff.
     */
    fun changedKeys(next: RuntimeSettingsSnapshot): Set<String> = snapshot?.let { previous ->
        if ((next.groups - previous.groups).isNotEmpty()) return emptySet()
        val keys = LinkedHashSet<String>()
        for (key in previous.values.keys + next.values.keys) {
            if (key == "prefs_epoch" || key == PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION) continue
            if (previous.values[key] != next.values[key]) keys += key
        }
        keys
    } ?: emptySet()
    @Synchronized fun accept(next: RuntimeSettingsSnapshot): Boolean {
        if (snapshot?.revision?.let { next.revision <= it } == true) return false
        snapshot = next
        return true
    }
    fun save(): Array<Any>? = snapshot?.let { arrayOf(it.revision, HashMap(it.values), ArrayList(it.groups)) }
    fun restore(saved: Any?) {
        val data = saved as? Array<*> ?: return
        val revision = data.getOrNull(0) as? Long ?: return
        val raw = data.getOrNull(1) as? Map<*, *> ?: return
        if (raw.keys.any { it !is String }) return
        val groups = if (data.size == 2) setOf(RuntimeSettingsGroups.ICONS, RuntimeSettingsGroups.ISLANDS)
            else (data.getOrNull(2) as? List<*>)?.takeIf { it.all { value -> value is String } }?.filterIsInstance<String>()?.toSet() ?: return
        @Suppress("UNCHECKED_CAST") // Keys were verified as String immediately above.
        RuntimeSettingsSnapshot.read(revision, raw as Map<String, *>, groups)?.let(::accept)
    }
}

internal object RuntimeSettingsStore {
    private val ledger = RuntimeSettingsLedger()
    private var target: String? = null
    fun configure(process: String, systemServer: Boolean) { target = RuntimeSettingsGroups.target(process, systemServer) }
    fun latest(): RuntimeSettingsSnapshot? = ledger.latest()
    fun values(): Map<String, Any>? = ledger.latest()?.values

    fun changedGroups(next: RuntimeSettingsSnapshot): Set<String> = ledger.changedGroups(next)
    fun changedKeys(next: RuntimeSettingsSnapshot): Set<String> = ledger.changedKeys(next)
    fun owns(key: String): Boolean = ledger.owns(key) && (target?.let { RuntimeSettingsGroups.owns(it, key) } ?: true)
    fun value(key: String): Any? = ledger.value(key)
    fun accept(next: RuntimeSettingsSnapshot): Boolean = ledger.accept(next).also {
        if (it) Preferences.invalidateRuntimeReadCache()
    }
    fun save(): Array<Any>? = ledger.save()
    fun restore(saved: Any?) {
        ledger.restore(saved)
        Preferences.invalidateRuntimeReadCache()
    }
}
