package com.takekazex.hypertweak.util

import org.junit.Assert.*
import org.junit.Test

class LogRepositoryTest {
    @Test fun appPortReadsFreshSnapshotsDeduplicatesAndSortsWithoutFrameworkAccess() {
        val early = LogRecord("2026-10-25 10:00:00.000", "I", "1", "App", "INFO", "early")
        val late = early.copy(time = "2026-10-25 11:00:00.000", message = "late")
        var stored = listOf(early, early)
        LogRepository.bindAppRecords { stored.joinToString("\n", transform = LogCodec::encode) }
        try {
            assertEquals(listOf("early"), LogRepository.read(false).records.map { it.message })
            stored = listOf(early, late)
            val snapshot = LogRepository.read(false)
            assertEquals(listOf("late", "early"), snapshot.records.map { it.message })
            assertEquals(listOf(0, 1), snapshot.records.map { it.index })
            assertEquals(LsposedLogReader.Status.ROOT_UNAVAILABLE, snapshot.lsposedStatus)
            assertTrue(LogRepository.export(snapshot).contains("late"))
        } finally { LogRepository.bindAppRecords { "" } }
    }
}
