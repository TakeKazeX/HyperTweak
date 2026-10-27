package com.takekazex.hypertweak.hook.bootstrap

/** Candidate owners are verified against the living Application, UID and loader by HookEntry. */
internal object SecurityHostRecovery {
    fun owner(process: String, systemServer: Boolean): String? {
        if (systemServer) return null
        val base = process.substringBefore(':')
        if (base in setOf("com.miui.securitycenter", "com.miui.securitycore", "com.lbe.security.miui") &&
            (process == base || process.startsWith("$base:") && process.length > base.length + 1)) return base
        return when (process) {
            "com.miui.securitycenter.remote", "com.miui.securitycenter.cache",
            "com.miui.securitycenter.power", "com.miui.securitycenter.bootaware", "com.miui.securitycenter.uniauth" ->
                "com.miui.securitycenter"
            "com.android.contacts", "com.android.contacts:business" -> "com.android.contacts"
            else -> process.substringBefore(':').takeIf { it == "com.miui.guardprovider" }
        }
    }
}
