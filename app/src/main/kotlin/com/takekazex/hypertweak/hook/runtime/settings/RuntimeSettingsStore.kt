package com.takekazex.hypertweak.hook.runtime.settings

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups

internal object RuntimeSettingsStore {
    private val ledger = RuntimeSettingsLedger()
    private var target: String? = null
    fun configure(process: String, systemServer: Boolean) { target = RuntimeSettingsGroups.target(process, systemServer) }

    /** A selection change can add this process to the IME group after it has started. */
    fun registerImeTargets(snapshot: RuntimeSettingsSnapshot) {
        val packages = snapshot.values[com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys.KEY_AOSP_IME_PACKAGES]
            as? Set<*> ?: return
        RuntimeSettingsGroups.registerImeTargets(packages.filterIsInstance<String>().toSet())
    }
    fun latest(): RuntimeSettingsSnapshot? = ledger.latest()
    fun values(): Map<String, Any>? = ledger.latest()?.values

    fun changedGroups(next: RuntimeSettingsSnapshot): Set<String> = ledger.changedGroups(next)
    fun changedKeys(next: RuntimeSettingsSnapshot): Set<String> = ledger.changedKeys(next)
    fun owns(key: String): Boolean = ledger.owns(key) && (target?.let { RuntimeSettingsGroups.owns(it, key) } ?: true)
    fun value(key: String): Any? = ledger.value(key)
    fun accept(next: RuntimeSettingsSnapshot): Boolean = ledger.accept(next).also {
        if (it) {
            RuntimeSettingsReadCache.memo.clear()
            HookSettingsSnapshots.refresh()
        }
    }
    fun save(): Array<Any>? = ledger.save()
    fun restore(saved: Any?) {
        ledger.restore(saved)
        RuntimeSettingsReadCache.memo.clear()
        HookSettingsSnapshots.refresh()
    }
}
