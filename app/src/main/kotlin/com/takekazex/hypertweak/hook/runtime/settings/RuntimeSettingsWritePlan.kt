package com.takekazex.hypertweak.hook.runtime.settings

/** A rejected libxposed commit updates its local map before IPC. Compare both that shadow and
 * the last acknowledgement: either one can differ from the actual daemon after a failed call. */
internal data class RuntimeSettingsWritePlan(val remove: Set<String>, val put: Map<String, Any>) {
    companion object {
        fun create(shadow: Map<String, *>, acknowledged: Map<String, Any>,
            desired: Map<String, Any>, complete: Boolean = false): RuntimeSettingsWritePlan {
            val keys = (shadow.keys + acknowledged.keys + desired.keys).filter(RuntimeSettingsSnapshot::owns)
            // A shadow-only key is a rejected editor's value the acknowledgement never contained,
            // so removing it is exactly the repair. `desired` always carries the acknowledged keys
            // (plus this commit's changes), which keeps epoch/revision bookkeeping in place.
            val remove = keys.filterTo(linkedSetOf()) { it !in desired }
            val put = desired.filter { (key, value) -> RuntimeSettingsSnapshot.owns(key) &&
                (complete || shadow[key] != value || acknowledged[key] != value) }
            return RuntimeSettingsWritePlan(remove, put)
        }
    }
}
