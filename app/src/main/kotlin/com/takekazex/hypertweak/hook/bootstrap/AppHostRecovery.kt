package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.rules.googleapp.GoogleAppProcessPolicy

/** Candidate owners are verified against the living Application, UID and loader by HookEntry. */
internal object AppHostRecovery {
    fun owner(process: String, systemServer: Boolean): String? {
        if (systemServer) return null
        val base = process.substringBefore(':')
        if (base in setOf("com.miui.securitycenter", "com.miui.securitycore", "com.lbe.security.miui",
                com.takekazex.hypertweak.settings.contract.CommunitySettings.PACKAGE,
                com.takekazex.hypertweak.settings.contract.MarketSettingsKeys.PACKAGE,
                com.takekazex.hypertweak.settings.contract.SmsSettingsKeys.PACKAGE) &&
            (process == base || process.startsWith("$base:") && process.length > base.length + 1)) return base
        return when (process) {
            GoogleAppProcessPolicy.UI_PROCESS -> GoogleAppProcessPolicy.PACKAGE
            "com.miui.securitycenter.remote", "com.miui.securitycenter.cache",
            "com.miui.securitycenter.power", "com.miui.securitycenter.bootaware", "com.miui.securitycenter.uniauth" ->
                "com.miui.securitycenter"
            "com.xiaomi.barrage" -> "com.xiaomi.barrage"
            "com.android.contacts", "com.android.contacts:business" -> "com.android.contacts"
            else -> process.substringBefore(':').takeIf { it == "com.miui.guardprovider" }
        }
    }
}
