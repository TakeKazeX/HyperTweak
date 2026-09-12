package com.takekazex.hypertweak.util.update

import java.net.URI
import java.net.URL

object UpdateNetworkPolicy {
    const val REPOSITORY_OWNER = "TakeKazeX"
    const val REPOSITORY_NAME = "HyperTweak"
    const val API_BASE = "https://api.github.com/repos/$REPOSITORY_OWNER/$REPOSITORY_NAME"
    const val WEB_BASE = "https://github.com/$REPOSITORY_OWNER/$REPOSITORY_NAME"
    const val PRESET_PROXY = "https://gh-proxy.com"

    val officialHosts: Set<String> = setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    fun normalizeProxy(raw: String?): String? {
        val value = raw?.trim()?.trimEnd('/').orEmpty()
        if (value.isEmpty()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        return value
    }

    fun effectiveProxy(raw: String?): String = normalizeProxy(raw) ?: PRESET_PROXY

    fun proxyHost(raw: String?): String? =
        runCatching { URI(effectiveProxy(raw)).host?.lowercase() }.getOrNull()

    /** Prefixes a GitHub endpoint only after its original URL has passed endpoint validation. */
    fun requestUrl(mode: UpdateProxyMode, proxy: String?, originalUrl: String): String =
        if (mode == UpdateProxyMode.OFFICIAL) originalUrl
        else "${effectiveProxy(proxy)}/$originalUrl"

    fun isAllowedApiUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("api.github.com", ignoreCase = true) &&
            uri.path.startsWith("/repos/$REPOSITORY_OWNER/$REPOSITORY_NAME/")
    }

    fun isAllowedGithubAssetUrl(url: String, assetName: String): Boolean {
        if (assetName.isBlank() || assetName.contains('/') || assetName.contains('\\')) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path ?: return false
        val prefix = "/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases/download/"
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            path.startsWith(prefix, ignoreCase = true) &&
            path.substringAfterLast('/') == assetName
    }

    fun isAllowedRequestHost(url: URL, mode: UpdateProxyMode, proxy: String?): Boolean {
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        val host = url.host.lowercase()
        return host in officialHosts ||
            (mode == UpdateProxyMode.THIRD_PARTY && host == proxyHost(proxy))
    }

    fun isHttps(url: URL): Boolean = url.protocol.equals("https", ignoreCase = true)
}
