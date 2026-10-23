package com.takekazex.hypertweak.root

import org.junit.Assert.*
import org.junit.Test

class BannerBatchProcessorTest {
    private class Backend : BannerBatchProcessor.Backend {
        val float = mutableSetOf<String>()
        val writes = mutableListOf<String>()
        var rejectReadback = false
        val channels = mutableListOf(
            BannerBatchProcessor.Channel("silent", 1, false),
            BannerBatchProcessor.Channel("low", 2, false),
            BannerBatchProcessor.Channel("closed", 0, false),
            BannerBatchProcessor.Channel("blocked-group", 4, true),
            BannerBatchProcessor.Channel("default", 3, false),
            BannerBatchProcessor.Channel("high", 4, false),
        )
        override fun enabled(app: BannerBatchProcessor.App) = app.pkg != "blocked.app"
        override fun channels(app: BannerBatchProcessor.App): List<BannerBatchProcessor.Channel> =
            if (app.pkg == "broken.app") throw IllegalStateException("RemoteException: notification lock") else channels
        override fun canFloat(app: BannerBatchProcessor.App, channel: BannerBatchProcessor.Channel?) = "${app.pkg}:${channel?.id}" in float
        override fun allowFloat(app: BannerBatchProcessor.App, channel: BannerBatchProcessor.Channel?) {
            val key = "${app.pkg}:${channel?.id}"
            writes += key
            if (!rejectReadback) float += key
        }
    }
    private val app = BannerBatchProcessor.App("allowed.app", 10001)

    @Test fun scanIsReadOnlyAndApplyOnlyTouchesEligibleFloatPermissions() {
        val backend = Backend()
        val apps = listOf(app, BannerBatchProcessor.App("blocked.app", 10002))
        val importanceBefore = backend.channels.map { it.importance }
        val scan = BannerBatchProcessor.run(apps, 0, false, backend)
        assertEquals(1, scan.apps)
        assertEquals(2, scan.channels)
        assertEquals(5, scan.skipped)
        assertTrue(backend.writes.isEmpty())
        val result = BannerBatchProcessor.run(apps, 0, true, backend)
        assertEquals(0, result.failed)
        assertEquals(listOf("allowed.app:null", "allowed.app:default", "allowed.app:high"), backend.writes)
        assertEquals(importanceBefore, backend.channels.map { it.importance })
        assertEquals(3, result.changed)
    }

    @Test fun effectiveWhitelistPermissionStillGetsAnExplicitPersistentSetting() {
        val backend = Backend()
        backend.float += listOf("allowed.app:null", "allowed.app:default", "allowed.app:high")
        val result = BannerBatchProcessor.run(listOf(app), 0, true, backend)
        assertEquals(0, result.failed)
        assertEquals(listOf("allowed.app:null", "allowed.app:default", "allowed.app:high"), backend.writes)
    }

    @Test fun appsWithoutEligibleChannelsAreNotModified() {
        val backend = Backend()
        backend.channels.removeAll { it.importance >= 3 && !it.blockedGroup }
        val result = BannerBatchProcessor.run(listOf(app), 0, true, backend)
        assertEquals(0, result.apps)
        assertEquals(0, result.channels)
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun perScopeFailuresKeepTheirCauseAndDoNotStopOtherApps() {
        val backend = Backend()
        val result = BannerBatchProcessor.run(listOf(BannerBatchProcessor.App("broken.app", 10003), app), 0, true, backend)
        assertEquals(1, result.failed)
        assertEquals(listOf("broken.app IllegalStateException: RemoteException: notification lock"), result.failures)
        assertEquals(3, backend.writes.size)
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
        assertEquals(listOf("allowed.app:null"), backend.writes)
    }

    @Test fun maximumImportanceIsAlsoEligible() {
        val backend = Backend()
        backend.channels.clear()
        backend.channels += BannerBatchProcessor.Channel("max", 5, false)
        assertEquals(1, BannerBatchProcessor.run(listOf(app), 0, true, backend).channels)
        assertEquals(listOf("allowed.app:null", "allowed.app:max"), backend.writes)
    }
}
