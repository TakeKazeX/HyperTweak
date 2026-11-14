package com.takekazex.hypertweak.hook.rules.system

/** Only installation signature failures are eligible; unknown identities keep the native result. */
internal object SharedUidInstallPolicy {
    data class Target(
        val appId: Int,
        val packageSystem: Boolean,
        val groupSystem: Boolean,
        val privileged: Boolean,
        val factoryPackage: Boolean,
    ) {
        val valid: Boolean get() = appId in 0..19_999
        val requiresSystemOption: Boolean get() = appId < 10_000 || packageSystem || groupSystem || privileged
    }

    fun allows(error: Int, systemEnabled: Boolean, target: Target): Boolean {
        if (!target.valid) return false
        if (target.requiresSystemOption) {
            return systemEnabled && (error == -7 || error == -8)
        }
        return error == -8 && !target.factoryPackage
    }
}
