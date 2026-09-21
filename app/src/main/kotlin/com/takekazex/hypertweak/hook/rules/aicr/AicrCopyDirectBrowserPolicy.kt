package com.takekazex.hypertweak.hook.rules.aicr

import java.net.URI
import java.util.Locale

/** Pure input gates shared by the legacy and intentUri Copy Direct routes. */
internal object AicrCopyDirectBrowserPolicy {
    const val XIAOMI_BROWSER = "com.android.browser"
    const val ACTION_VIEW = "android.intent.action.VIEW"

    fun normalizeHttpUrl(rawUrl: String): String? {
        val value = rawUrl.trim()
        if (value.isEmpty()) return null

        val hasWebScheme = value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        if (!hasWebScheme && hasUnsupportedExplicitScheme(value)) return null

        val normalized = if (hasWebScheme) value else "https://$value"
        val parsed = runCatching { URI(normalized) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        if (parsed.rawAuthority.isNullOrBlank()) return null
        return normalized
    }

    fun targetsXiaomiWebAction(
        packageName: String?,
        componentPackage: String?,
        action: String?,
        scheme: String?
    ): Boolean {
        val targetsXiaomiBrowser = packageName == XIAOMI_BROWSER || componentPackage == XIAOMI_BROWSER
        val opensWeb = scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
        return targetsXiaomiBrowser && opensWeb && (action == null || action == ACTION_VIEW)
    }

    private fun hasUnsupportedExplicitScheme(value: String): Boolean {
        if (value.contains("://")) return true
        val prefix = value.substringBefore(':', missingDelimiterValue = "")
        return prefix in setOf("mailto", "tel", "file", "content", "intent", "ftp", "data")
    }
}
