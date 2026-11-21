package com.takekazex.hypertweak.hook.rules.systemui.volume.app

import com.takekazex.hypertweak.hook.rules.systemui.volume.app.AppVolumeCandidates.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class AppVolumeCandidatesTest {
    private val started = AppVolumeCandidates.STATE_STARTED
    private val paused = 3

    @Test fun onlyPlayersTheSystemWouldAdjustGetAColumn() {
        val rows = AppVolumeCandidates.select(listOf(
            Player(10100, started, usage = 1, stream = 3, key = "music"),
            Player(1000, started, usage = 1, stream = 3, key = "android.uid.system:1000"),
            Player(901000, started, usage = 1, stream = 3, key = "other.user.system"),
            Player(10101, started, usage = 5, stream = 5, key = "notifier"),
            Player(10102, started, usage = 0, stream = 3, key = "game"),
            Player(10103, started, usage = 1, stream = 3, key = "com.miui.miwallpaper"),
            Player(10104, started, usage = 1, stream = 3, key = null),
        ))
        assertEquals(listOf("music", "game"), rows.map { it.key })
    }

    @Test fun cloneAndExtraPlayersShareTheGainKeyWithOneRepresentativePerUid() {
        val rows = AppVolumeCandidates.select(listOf(
            Player(10100, paused, 1, 3, "video", token = "paused"),
            Player(10100, started, 1, 3, "video", token = "main"),
            Player(99910100, started, 1, 3, "video", token = "clone"),
        ))
        assertEquals(1, rows.size)
        assertEquals(listOf(10100, 99910100), rows.single().uids)
        assertEquals(listOf("main", "clone"), rows.single().players.map { it.token })
    }

    @Test fun pausedAppsAppearOnlyWhileTheirColumnIsShowingAndKeepTheirPlace() {
        val players = listOf(
            Player(10200, started, 1, 3, "podcast"),
            Player(10100, paused, 1, 3, "music"),
        )
        assertEquals(listOf("podcast"), AppVolumeCandidates.select(players).map { it.key })
        assertEquals(listOf("music", "podcast"), AppVolumeCandidates.select(players, retained = listOf("music", "gone")).map { it.key })
    }

    @Test fun percentRoundsTheGainRatio() {
        assertEquals(0, AppVolumeCandidates.percent(-1f))
        assertEquals(50, AppVolumeCandidates.percent(0.495f))
        assertEquals(100, AppVolumeCandidates.percent(1.2f))
    }
}
