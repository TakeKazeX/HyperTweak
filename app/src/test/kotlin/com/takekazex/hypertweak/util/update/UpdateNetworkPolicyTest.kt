package com.takekazex.hypertweak.util.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class UpdateNetworkPolicyTest {
    private val owner = UpdateNetworkPolicy.REPOSITORY_OWNER
    private val repo = UpdateNetworkPolicy.REPOSITORY_NAME

    @Test
    fun `only https proxies without credentials, query or fragment are accepted`() {
        assertEquals("https://gh-proxy.com", UpdateNetworkPolicy.normalizeProxy("https://gh-proxy.com/"))
        assertEquals("https://gh-proxy.com", UpdateNetworkPolicy.normalizeProxy("  https://gh-proxy.com  "))
        assertNull(UpdateNetworkPolicy.normalizeProxy(null))
        assertNull(UpdateNetworkPolicy.normalizeProxy(""))
        assertNull(UpdateNetworkPolicy.normalizeProxy("   "))
        // Decision D13: cleartext is never permitted, so an http proxy is not a usable value.
        assertNull(UpdateNetworkPolicy.normalizeProxy("http://gh-proxy.com"))
        // A credential or a query in the base would leak into every request URL.
        assertNull(UpdateNetworkPolicy.normalizeProxy("https://user:pass@gh-proxy.com"))
        assertNull(UpdateNetworkPolicy.normalizeProxy("https://gh-proxy.com/?token=x"))
        assertNull(UpdateNetworkPolicy.normalizeProxy("https://gh-proxy.com/#frag"))
    }

    @Test
    fun `a blank or invalid proxy falls back to the preset rather than creating a dead state`() {
        assertEquals(UpdateNetworkPolicy.PRESET_PROXY, UpdateNetworkPolicy.effectiveProxy(null))
        assertEquals(UpdateNetworkPolicy.PRESET_PROXY, UpdateNetworkPolicy.effectiveProxy(""))
        assertEquals(UpdateNetworkPolicy.PRESET_PROXY, UpdateNetworkPolicy.effectiveProxy("http://bad"))
        assertEquals("https://example.com", UpdateNetworkPolicy.effectiveProxy("https://example.com"))
    }

    @Test
    fun `the proxy prefix is only applied in third party mode`() {
        val api = "${UpdateNetworkPolicy.API_BASE}/releases/latest"
        assertEquals(api, UpdateNetworkPolicy.requestUrl(UpdateProxyMode.OFFICIAL, null, api))
        assertEquals(
            "${UpdateNetworkPolicy.PRESET_PROXY}/$api",
            UpdateNetworkPolicy.requestUrl(UpdateProxyMode.THIRD_PARTY, null, api)
        )
        assertEquals(
            "https://example.com/$api",
            UpdateNetworkPolicy.requestUrl(UpdateProxyMode.THIRD_PARTY, "https://example.com", api)
        )
    }

    @Test
    fun `api requests must stay inside this repository over https`() {
        assertTrue(
            UpdateNetworkPolicy.isAllowedApiUrl(
                "https://api.github.com/repos/$owner/$repo/releases/tags/ci-latest"
            )
        )
        assertFalse(UpdateNetworkPolicy.isAllowedApiUrl("http://api.github.com/repos/$owner/$repo/releases/latest"))
        assertFalse(UpdateNetworkPolicy.isAllowedApiUrl("https://api.github.com/repos/other/repo/releases/latest"))
        assertFalse(UpdateNetworkPolicy.isAllowedApiUrl("https://evil.example/repos/$owner/$repo/releases/latest"))
        assertFalse(UpdateNetworkPolicy.isAllowedApiUrl("not a url"))
    }

    @Test
    fun `an apk download url must be one of this repository's release assets`() {
        val asset = "HyperTweak-v1.8.0-275-release.apk"
        val good = "https://github.com/$owner/$repo/releases/download/v1.8.0/$asset"
        assertTrue(UpdateNetworkPolicy.isAllowedGithubAssetUrl(good, asset))

        // The name inside the URL has to be the asset the metadata named: this is what stops a
        // rewritten build-info.json from pointing the download at some other file.
        assertFalse(UpdateNetworkPolicy.isAllowedGithubAssetUrl(good, "Other.apk"))
        // Path traversal in the asset name would escape the release directory.
        assertFalse(UpdateNetworkPolicy.isAllowedGithubAssetUrl(good, "../../etc/passwd"))
        assertFalse(UpdateNetworkPolicy.isAllowedGithubAssetUrl(good, "nested/$asset"))
        // A different host and a different repository are both refused.
        assertFalse(
            UpdateNetworkPolicy.isAllowedGithubAssetUrl(
                "https://objects.githubusercontent.com/$owner/$repo/releases/download/v1.8.0/$asset",
                asset
            )
        )
        assertFalse(
            UpdateNetworkPolicy.isAllowedGithubAssetUrl(
                "https://github.com/attacker/$repo/releases/download/v1.8.0/$asset",
                asset
            )
        )
        assertFalse(UpdateNetworkPolicy.isAllowedGithubAssetUrl(good, "  "))
    }

    /**
     * The official asset endpoint 302s to the release-assets CDN. A redirect policy that forgot that
     * host would refuse GitHub's own download, which is exactly the defect the design review caught.
     */
    @Test
    fun `the redirect allowlist covers the cdn github redirects release assets to`() {
        assertTrue(
            UpdateNetworkPolicy.isAllowedRequestHost(
                URL("https://release-assets.githubusercontent.com/signed/asset"),
                UpdateProxyMode.OFFICIAL,
                null
            )
        )
        assertTrue(
            UpdateNetworkPolicy.isAllowedRequestHost(
                URL("https://objects.githubusercontent.com/x"),
                UpdateProxyMode.OFFICIAL,
                null
            )
        )
        assertTrue(
            UpdateNetworkPolicy.isAllowedRequestHost(
                URL("https://github.com/$owner/$repo/releases/download/v1.8.0/x.apk"),
                UpdateProxyMode.OFFICIAL,
                null
            )
        )
    }

    @Test
    fun `off-host and cleartext requests are refused in both modes`() {
        assertFalse(
            UpdateNetworkPolicy.isAllowedRequestHost(
                URL("https://evil.example/x"),
                UpdateProxyMode.OFFICIAL,
                null
            )
        )
        // The proxy host is only legitimate when the user actually selected the proxy.
        assertFalse(
            UpdateNetworkPolicy.isAllowedRequestHost(
                URL("https://gh-proxy.com/x"),
                UpdateProxyMode.OFFICIAL,
                null
            )
        )
        assertTrue(
            UpdateNetworkPolicy.isAllowedRequestHost(
                URL("https://gh-proxy.com/x"),
                UpdateProxyMode.THIRD_PARTY,
                null
            )
        )
        assertFalse(
            UpdateNetworkPolicy.isAllowedRequestHost(
                URL("http://github.com/$owner/$repo/releases/download/v1.8.0/x.apk"),
                UpdateProxyMode.OFFICIAL,
                null
            )
        )
    }
}
