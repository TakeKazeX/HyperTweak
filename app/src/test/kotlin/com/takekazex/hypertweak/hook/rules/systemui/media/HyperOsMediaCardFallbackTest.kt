package com.takekazex.hypertweak.hook.rules.systemui.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOsMediaCardFallbackTest {
    private val music = 0 to "com.example.music"
    private val video = 0 to "com.example.video"

    @Test
    fun singleAppUsesSystemCardEvenWithMultipleSessions() {
        assertTrue(shouldUseNativeMediaCard(listOf(music), hasSuppressedSessions = false))
        assertTrue(shouldUseNativeMediaCard(listOf(music, music), hasSuppressedSessions = false))
    }

    @Test
    fun multipleAppsOrProfilesKeepSeparatePages() {
        assertFalse(shouldUseNativeMediaCard(listOf(music, video), hasSuppressedSessions = false))
        assertFalse(shouldUseNativeMediaCard(listOf(music, 10 to music.second), hasSuppressedSessions = false))
    }

    @Test
    fun fallbackDoesNotResurfaceSuppressedSessionsOrMergeUnknownApps() {
        assertFalse(shouldUseNativeMediaCard(listOf(music), hasSuppressedSessions = true))
        assertFalse(shouldUseNativeMediaCard(listOf(null, null), hasSuppressedSessions = false))
        assertTrue(shouldUseNativeMediaCard(listOf(null), hasSuppressedSessions = false))
        assertFalse(shouldUseNativeMediaCard(emptyList(), hasSuppressedSessions = false))
    }
}
