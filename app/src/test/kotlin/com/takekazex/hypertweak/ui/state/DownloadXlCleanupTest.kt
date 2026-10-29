package com.takekazex.hypertweak.ui.state

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DownloadXlCleanupTest {
    @Test fun `failed check never enables blocking`() = runBlocking {
        val commits = mutableListOf<Boolean>()
        var failures = 0
        val cleanup = DownloadXlCleanup(this, exists = { null })
        cleanup.checkAndEnable(commits::add) { failures++ }
        yield()
        assertTrue(commits.isEmpty())
        assertEquals(1, failures)
        assertFalse(cleanup.state.value.busy)
        assertFalse(cleanup.state.value.confirmDelete)
    }

    @Test fun `keeping the folder never deletes and a new enable request can check again`() = runBlocking {
        var deletes = 0
        val commits = mutableListOf<Boolean>()
        val cleanup = DownloadXlCleanup(this, exists = { true }, delete = { deletes++; true })
        cleanup.checkAndEnable(commits::add) { fail() }
        yield()
        assertEquals(listOf(true), commits)
        assertTrue(cleanup.state.value.confirmDelete)
        cleanup.dismiss()
        cleanup.confirm { fail("dismissal is not confirmation") }
        yield()
        assertEquals(0, deletes)
        cleanup.disable(commits::add)
        cleanup.checkAndEnable(commits::add) { fail() }
        yield()
        assertEquals(listOf(true, false, true), commits)
        assertTrue(cleanup.state.value.confirmDelete)
    }

    @Test fun `absent directory enables without prompting`() = runBlocking {
        var enabled = false
        val cleanup = DownloadXlCleanup(this, exists = { false })
        cleanup.checkAndEnable({ enabled = it }, { fail() })
        yield()
        assertTrue(enabled)
        assertEquals(DownloadXlCleanup.State(), cleanup.state.value)
    }

    @Test fun `pending check is single flight and disable rejects its late result`() = runBlocking {
        val result = CompletableDeferred<Boolean?>()
        var checks = 0
        val commits = mutableListOf<Boolean>()
        val cleanup = DownloadXlCleanup(this, exists = { checks++; result.await() })
        cleanup.checkAndEnable(commits::add) { fail() }
        cleanup.checkAndEnable(commits::add) { fail() }
        yield()
        cleanup.disable(commits::add)
        result.complete(true)
        yield()
        assertEquals(1, checks)
        assertEquals(listOf(false), commits)
        assertEquals(DownloadXlCleanup.State(), cleanup.state.value)
    }

    @Test fun `confirmed deletion survives page collector leaving and rejects duplicate clicks`() = runBlocking {
        val result = CompletableDeferred<Boolean?>()
        var deletes = 0
        var success: Boolean? = null
        val cleanup = DownloadXlCleanup(this, exists = { true }, delete = { deletes++; result.await() })
        cleanup.checkAndEnable({}, { fail() })
        yield()
        cleanup.confirm { success = it }
        cleanup.confirm { fail("duplicate deletion") }
        yield()
        assertTrue(cleanup.state.value.busy)
        result.complete(false)
        yield()
        assertEquals(1, deletes)
        assertEquals(false, success)
        assertEquals(DownloadXlCleanup.State(), cleanup.state.value)
    }
}
