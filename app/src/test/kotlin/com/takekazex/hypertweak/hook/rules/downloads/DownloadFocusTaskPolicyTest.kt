package com.takekazex.hypertweak.hook.rules.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadFocusTaskPolicyTest {
    @Test fun `network and reason-specific waits keep the active task identity`() {
        val active = DownloadFocusTaskPolicy.splitTag("1:source.app", 42, "source.app")
        assertEquals("1:source.app:42", active)
        assertEquals(active, DownloadFocusTaskPolicy.splitTag("2:source.app", 42, "source.app"))
        assertEquals(active, DownloadFocusTaskPolicy.splitTag("5:503:source.app", 42, "source.app"))
    }

    @Test fun `successful files keep the native completion type and independent identities`() {
        val first = DownloadFocusTaskPolicy.splitTag("3:source.app:success", 42, "source.app")!!
        val second = DownloadFocusTaskPolicy.splitTag("3:source.app:success", 43, "source.app")!!
        assertEquals("3:source.app:success:42", first)
        assertNotEquals(first, second)
        assertTrue(DownloadFocusTaskPolicy.completed(first))
        assertEquals("3", first.substringBefore(':'))
        assertFalse(DownloadFocusTaskPolicy.completed("1:source.app:42"))
    }

    @Test fun `failed hidden aggregate and unknown notifications are not promoted`() {
        listOf(null, "3:source.app:failed", "4: internal", "7:source.app", "3:other.app:success")
            .forEach { assertNull(DownloadFocusTaskPolicy.splitTag(it, 42, "source.app")) }
        assertNull(DownloadFocusTaskPolicy.splitTag("1:source.app", -1, "source.app"))
    }

    @Test fun `retained refresh excludes unposted history and deduplicates aggregate members`() {
        val known = mapOf(1L to "paused", 2L to "completed", 3L to "old history")
        assertEquals(listOf("paused", "completed"), DownloadFocusTaskPolicy.postedTasks(known,
            listOf(longArrayOf(1), longArrayOf(2, 1))))
    }

    @Test fun `incomplete current identities reject the entire retained refresh`() {
        val known = mapOf(1L to "paused")
        assertNull(DownloadFocusTaskPolicy.postedTasks(known, emptyList()))
        assertNull(DownloadFocusTaskPolicy.postedTasks(known, listOf(null)))
        assertNull(DownloadFocusTaskPolicy.postedTasks(known, listOf(longArrayOf())))
        assertNull(DownloadFocusTaskPolicy.postedTasks(known, listOf(longArrayOf(1), longArrayOf(99))))
    }
}
