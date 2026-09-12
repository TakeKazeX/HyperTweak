package com.takekazex.hypertweak.util.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateModelsTest {
    /**
     * `wireValue` is persisted in `Preferences` and written by the release workflows into
     * `build-info.json`, so these strings are a contract shared with the release workflows in
     * `.github/`.
     */
    @Test
    fun `channel wire values match the published metadata`() {
        assertEquals("ci", UpdateChannel.CI.wireValue)
        assertEquals("stable", UpdateChannel.STABLE.wireValue)
        assertEquals("/releases/tags/ci-latest", UpdateChannel.CI.releaseApiPath)
        assertEquals("/releases/latest", UpdateChannel.STABLE.releaseApiPath)
    }

    @Test
    fun `unreadable stored values fall back to a working default instead of a dead state`() {
        assertEquals(UpdateChannel.CI, UpdateChannel.fromValue("ci"))
        assertEquals(UpdateChannel.CI, UpdateChannel.fromValue(" CI "))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromValue(null))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromValue("nonsense"))
        assertEquals(UpdateProxyMode.THIRD_PARTY, UpdateProxyMode.fromValue("third_party"))
        assertEquals(UpdateProxyMode.OFFICIAL, UpdateProxyMode.fromValue(null))
        assertEquals(UpdateProxyMode.OFFICIAL, UpdateProxyMode.fromValue("nonsense"))
    }

    @Test
    fun `check interval indices round trip and clamp to daily`() {
        assertEquals(0, UpdateCheckInterval.DAILY.index)
        assertEquals(1, UpdateCheckInterval.WEEKLY.index)
        assertEquals(2, UpdateCheckInterval.MONTHLY.index)
        assertEquals(3, UpdateCheckInterval.NEVER.index)
        UpdateCheckInterval.entries.forEach { interval ->
            assertEquals(interval, UpdateCheckInterval.fromIndex(interval.index))
        }
        assertEquals(UpdateCheckInterval.DAILY, UpdateCheckInterval.fromIndex(99))
        assertNull(UpdateCheckInterval.NEVER.intervalMillis)
        assertEquals(24L * 60L * 60L * 1000L, UpdateCheckInterval.DAILY.intervalMillis)
    }

    /** The cache and the skip list are keyed per channel, version *and* commit. */
    @Test
    fun `cache key separates the channel, the version code and the commit`() {
        val base = info(channel = UpdateChannel.CI, versionCode = 351, commit = "abc1234")
        assertEquals("ci|351|abc1234", base.cacheKey)
        assertTrue(base.cacheKey != info(channel = UpdateChannel.STABLE, versionCode = 351, commit = "abc1234").cacheKey)
        assertTrue(base.cacheKey != info(channel = UpdateChannel.CI, versionCode = 352, commit = "abc1234").cacheKey)
        assertTrue(base.cacheKey != info(channel = UpdateChannel.CI, versionCode = 351, commit = "def5678").cacheKey)
        // A legacy release carries no code or commit and must still produce a stable key.
        assertEquals("stable|-1|", info(channel = UpdateChannel.STABLE, versionCode = null, commit = null).cacheKey)
    }

    @Test
    fun `notes prefer the device language and never come back empty when one side exists`() {
        val both = UpdateNotes(zh = "中文", en = "English")
        assertEquals("中文", both.preferred(isChinese = true))
        assertEquals("English", both.preferred(isChinese = false))
        // A one-sided note must still render, in either direction, rather than showing nothing.
        assertEquals("English", UpdateNotes(zh = null, en = "English").preferred(isChinese = true))
        assertEquals("中文", UpdateNotes(zh = "中文", en = null).preferred(isChinese = false))
        assertEquals("中文", UpdateNotes(zh = "中文", en = "  ").preferred(isChinese = false))
        assertNull(UpdateNotes(zh = null, en = null).preferred(isChinese = true))
    }

    @Test
    fun `integrity metadata is only trusted when both a size and a full sha256 are present`() {
        val sha = "a".repeat(64)
        assertTrue(info(channel = UpdateChannel.CI, versionCode = 1, commit = "a", size = 1024L, sha = sha).hasIntegrityMetadata)
        assertFalse(info(channel = UpdateChannel.CI, versionCode = 1, commit = "a", size = null, sha = sha).hasIntegrityMetadata)
        assertFalse(info(channel = UpdateChannel.CI, versionCode = 1, commit = "a", size = 1024L, sha = null).hasIntegrityMetadata)
        assertFalse(info(channel = UpdateChannel.CI, versionCode = 1, commit = "a", size = 0L, sha = sha).hasIntegrityMetadata)
        assertFalse(info(channel = UpdateChannel.CI, versionCode = 1, commit = "a", size = 1024L, sha = "short").hasIntegrityMetadata)
    }

    @Test
    fun `progress fraction is only reported when the total is known`() {
        assertEquals(0.5f, DownloadProgress(downloadedBytes = 50L, totalBytes = 100L).fraction!!, 0.0001f)
        assertNull(DownloadProgress(downloadedBytes = 50L, totalBytes = null).fraction)
        assertNull(DownloadProgress(downloadedBytes = 50L, totalBytes = 0L).fraction)
        // A stalled or over-reported stream must not render outside the bar.
        assertEquals(1f, DownloadProgress(downloadedBytes = 150L, totalBytes = 100L).fraction!!, 0.0001f)
    }

    private fun info(
        channel: UpdateChannel,
        versionCode: Long?,
        commit: String?,
        size: Long? = 1024L,
        sha: String? = "a".repeat(64)
    ) = UpdateInfo(
        channel = channel,
        tagName = channel.defaultTag,
        versionName = "1.8.0",
        versionCode = versionCode,
        commit = commit,
        shortCommit = commit?.take(7).orEmpty(),
        builtAt = null,
        compareBase = null,
        apkAsset = "HyperTweak.apk",
        apkSize = size,
        apkSha256 = sha,
        minSdk = 36,
        targetSdk = 37,
        notes = null,
        releaseUrl = "https://github.com/x/y/releases/tag/${channel.defaultTag}",
        downloadUrl = "https://github.com/x/y/releases/download/${channel.defaultTag}/HyperTweak.apk",
        publishedAt = null,
        distance = null
    )
}
