package com.takekazex.hypertweak.hook.runtime.settings

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups

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
        if (it) RuntimeSettingsReadCache.memo.clear()
    }
    fun save(): Array<Any>? = ledger.save()
    fun restore(saved: Any?) {
        ledger.restore(saved)
        RuntimeSettingsReadCache.memo.clear()
    }
}
