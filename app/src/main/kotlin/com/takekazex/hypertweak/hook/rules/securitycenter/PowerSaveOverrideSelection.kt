package com.takekazex.hypertweak.hook.rules.securitycenter

/** Flatten the old master-gated snapshot without enabling dormant sibling switches. */
internal object PowerSaveOverrideSelection {
    fun edit(master: Boolean, stored: Map<String, Boolean>, key: String, enabled: Boolean): Map<String, Boolean> {
        require(key in stored)
        return stored.mapValues { (feature, value) -> if (feature == key) enabled else master && value }
    }
}
