package com.takekazex.hypertweak.hook

/** Restricts state-free upgrade recovery to this feature family's verified host processes. */
internal object SecurityHostRecovery {
    fun owner(process: String, systemServer: Boolean): String? {
        if (systemServer) return null
        return when (process) {
            "com.miui.securitycenter", "com.miui.securitycenter.remote", "com.miui.securitycenter.cache" ->
                "com.miui.securitycenter"
            "com.android.contacts", "com.android.contacts:business" -> "com.android.contacts"
            else -> process.substringBefore(':').takeIf { it == "com.miui.guardprovider" }
        }
    }
}
