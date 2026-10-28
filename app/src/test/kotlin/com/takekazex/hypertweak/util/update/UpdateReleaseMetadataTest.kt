package com.takekazex.hypertweak.util.update

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseMetadataTest {
    private fun envelope(json: String): String = "<!-- hypertweak-update:v1:" +
        Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8)) + " -->"

    @Test fun `body preserves bilingual notes and embedded comment characters`() {
        val json = JSONObject().put("versionCode", 123).put("notes", JSONObject().put("zh", "中文 -->\n更新"))
        val parsed = UpdateReleaseMetadata.parse("Visible notes\n" + envelope(json.toString()))!!
        assertEquals(123, parsed.getInt("versionCode"))
        assertEquals("中文 -->\n更新", parsed.getJSONObject("notes").getString("zh"))
    }

    @Test fun `historical body does not require a metadata download`() {
        assertNull(UpdateReleaseMetadata.parse("### Changes\n- Fix"))
        assertEquals(812L, UpdateReleaseMetadata.legacyVersionCode("HyperTweak-v1.8.0-812-release.apk"))
        assertEquals(812L, UpdateReleaseMetadata.legacyVersionCode("HyperTweak-v1.8.0-812-release-debug-signed.apk"))
        assertNull(UpdateReleaseMetadata.legacyVersionCode("HyperTweak-v1.8.0.apk"))
    }

    @Test fun `malformed or duplicate metadata cannot silently fall back to legacy`() {
        for (body in listOf("<!-- hypertweak-update:v1:??? -->", envelope("not JSON"), envelope("{}") + envelope("{}"))) {
            assertThrows(UpdateException.InvalidMetadata::class.java) { UpdateReleaseMetadata.parse(body) }
        }
    }

    @Test fun `rolling body must match the uploaded APK size and digest`() {
        val metadata = JSONObject().put("apkAsset", "a.apk").put("apkSize", 100).put("apkSha256", "a".repeat(64))
        val asset = JSONObject().put("name", "a.apk").put("size", 100).put("digest", "sha256:" + "a".repeat(64))
        UpdateReleaseMetadata.validateAsset(metadata, asset)
        for (changed in listOf(JSONObject(asset.toString()).put("size", 101),
            JSONObject(asset.toString()).put("digest", "sha256:" + "b".repeat(64)),
            JSONObject(asset.toString()).put("name", "b.apk"))) {
            assertThrows(UpdateException.InvalidMetadata::class.java) { UpdateReleaseMetadata.validateAsset(metadata, changed) }
        }
    }

    @Test fun `release shortcut has a trusted destination for both channels without cached info`() {
        UpdateChannel.entries.forEach { assertTrue(UpdateNetworkPolicy.isAllowedReleasePageUrl(UpdateNetworkPolicy.releasePageUrl(it))) }
        assertFalse(UpdateNetworkPolicy.isAllowedReleasePageUrl("https://github.com/other/repo/releases/latest"))
        assertFalse(UpdateNetworkPolicy.isAllowedReleasePageUrl("https://github.com.evil/TakeKazeX/HyperTweak/releases/latest"))
        assertFalse(UpdateNetworkPolicy.isAllowedReleasePageUrl("https://github.com/TakeKazeX/HyperTweak/releases/download/x/a.apk"))
    }
}
