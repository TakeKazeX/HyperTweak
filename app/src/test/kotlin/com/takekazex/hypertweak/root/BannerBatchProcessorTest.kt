package com.takekazex.hypertweak.root

import org.junit.Assert.*
import org.junit.Test

class BannerBatchProcessorTest {
    private class Backend : BannerBatchProcessor.Backend {
        val float = mutableSetOf<String>()
        val writes = mutableListOf<String>()
        var rejectReadback = false
        val channels = mutableListOf(
            BannerBatchProcessor.Channel("muted", 2, false, null),
            BannerBatchProcessor.Channel("closed", 0, false, null),
            BannerBatchProcessor.Channel("blocked-group", 4, true, null),
            BannerBatchProcessor.Channel("urgent", 4, false, null)
        )
        override fun enabled(app: BannerBatchProcessor.App) = app.pkg != "blocked.app"
        override fun channels(app: BannerBatchProcessor.App) = channels
        override fun canFloat(app: BannerBatchProcessor.App, channel: BannerBatchProcessor.Channel?) = "${app.pkg}:${channel?.id}" in float
        override fun allowFloat(app: BannerBatchProcessor.App, channel: BannerBatchProcessor.Channel?) {
            val key = "${app.pkg}:${channel?.id}"
            writes += key
            if (!rejectReadback) float += key
        }
        override fun raiseImportance(app: BannerBatchProcessor.App, channel: BannerBatchProcessor.Channel) {
            writes += "importance:${channel.id}"
            channels[channels.indexOf(channel)] = BannerBatchProcessor.Channel(channel.id, 4, false, null)
        }
    }
    private val app = BannerBatchProcessor.App("allowed.app", 10001)

    @Test fun scanIsReadOnlyAndApplySkipsBlockedPermissionsAndGroups() {
        val backend = Backend()
        val apps = listOf(app, BannerBatchProcessor.App("blocked.app", 10002))
        val scan = BannerBatchProcessor.run(apps, 0, false, backend)
        assertEquals(1, scan.apps)
        assertEquals(2, scan.channels)
        assertEquals(3, scan.skipped)
        assertTrue(backend.writes.isEmpty())
        val result = BannerBatchProcessor.run(apps, 0, true, backend)
        assertEquals(0, result.failed)
        assertTrue(backend.writes.contains("importance:muted"))
        assertFalse(backend.writes.any { it.contains("closed") || it.contains("blocked") || it == "importance:urgent" })
        backend.writes.clear()
        assertEquals(0, BannerBatchProcessor.run(apps, 0, true, backend).changed)
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun userMismatchIsRejectedBeforeAnyWrite() {
        val backend = Backend()
        try {
            BannerBatchProcessor.run(listOf(app, BannerBatchProcessor.App("work.app", 1010001)), 0, true, backend)
            fail("Cross-user input must fail")
        } catch (_: IllegalArgumentException) { assertTrue(backend.writes.isEmpty()) }
    }

    @Test fun failedReadbackIsReportedWithoutClaimingACompletedMutation() {
        val backend = Backend().apply { rejectReadback = true }
        val result = BannerBatchProcessor.run(listOf(app), 0, true, backend)
        assertEquals(1, result.failed)
        assertEquals(0, result.changed)
        assertFalse(backend.writes.any { it.startsWith("importance:") })
    }
}
