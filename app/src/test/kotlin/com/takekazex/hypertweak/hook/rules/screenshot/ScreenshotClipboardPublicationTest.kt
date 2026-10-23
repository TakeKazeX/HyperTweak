package com.takekazex.hypertweak.hook.rules.screenshot

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class ScreenshotClipboardPublicationTest {
    private fun queue() = ScreenshotClipboardPublication { throw AssertionError(it) }
    @Test fun pendingUrisArePublishedOnlyAfterSuccessfulWriteAndScopeExit() {
        val queue = queue()
        val events = mutableListOf<String>()
        queue.saving("uri", { it }) {
            assertTrue(queue.defer("uri") { events += "publish" })
            assertTrue(events.isEmpty())
            events += "clear pending"
            true
        }
        assertEquals(listOf("clear pending", "publish"), events)
        assertFalse(queue.defer("uri") { fail("stale save") })
    }
    @Test fun failureAndExceptionsDropPendingPublicationsWithoutChangingTheSaveFailure() {
        val queue = queue()
        assertFalse(queue.saving("uri", { it }) { queue.defer("uri") { fail("failed save") }; false })
        val original = IllegalStateException("write failed")
        try {
            queue.saving("uri", { it == true }) { queue.defer("uri") { fail("throwing save") }; throw original }
            fail("expected original")
        } catch (t: IllegalStateException) { assertSame(original, t) }
        assertFalse(queue.defer("uri") { fail("stale save") })
    }
    @Test fun nestedUrisStayIsolatedAndOnlyTheLastRequestForOneSaveIsPublished() {
        val queue = queue()
        val events = mutableListOf<String>()
        queue.saving("outer", { it }) {
            queue.defer("outer") { fail("replaced publication") }
            queue.defer("outer") { events += "outer" }
            queue.saving("inner", { it }) { queue.defer("inner") { events += "inner" }; true }
            assertEquals(listOf("inner"), events)
            true
        }
        assertEquals(listOf("inner", "outer"), events)
    }
    @Test fun anotherThreadCannotDeferIntoThisSaveAndPublicationErrorsDoNotFailTheImage() {
        var errors = 0
        val queue = ScreenshotClipboardPublication { errors++ }
        val other = Executors.newSingleThreadExecutor()
        try {
            assertTrue(queue.saving("uri", { it }) {
                assertFalse(other.submit<Boolean> { queue.defer("uri") {} }.get())
                queue.defer("uri") { error("clipboard unavailable") }
                true
            })
        } finally { other.shutdownNow() }
        assertEquals(1, errors)
    }
    @Test fun fullSaveOwnsInnerWriterPublicationUntilItsOwnSuccessIsKnown() {
        val queue = queue()
        var published = false
        queue.saving(null, { it }) {
            queue.saving("uri", { it }) {
                assertTrue(queue.defer("uri") { published = true })
                true
            }
            assertFalse(published)
            false
        }
        assertFalse(published)
    }
    @Test fun successfulSaveAndMediaCommitCanArriveInEitherOrderAndPublishOnce() {
        val commits = ScreenshotClipboardCommits({ throw AssertionError(it) })
        var visible = false
        var publications = 0
        commits.await("uri", { visible }) { publications++ }
        assertEquals(0, publications)
        visible = true
        commits.committed("uri")
        commits.committed("uri")
        assertEquals(1, publications)
        // A commit notification before enrollment is recovered by the immediate readiness check.
        commits.committed("other")
        commits.await("other", { true }) { publications++ }
        assertEquals(2, publications)
    }
    @Test fun retirementExpiryAndConfirmationFailureDropQueuedUris() {
        var time = 0L
        var errors = 0
        val commits = ScreenshotClipboardCommits({ errors++ }, { time })
        var ready = false
        commits.await("old", { ready }) { fail("expired URI") }
        time = 30_000
        ready = true
        commits.committed("old")
        ready = false
        commits.await("retired", { ready }) { fail("retired owner") }
        commits.clear()
        ready = true
        commits.committed("retired")
        commits.await("failed", { error("provider denied") }) { fail("unconfirmed URI") }
        assertEquals(1, errors)
    }

    @Test fun nestedFullTasksDoNotMoveInnerPublicationIntoAFailingOuterTask() {
        val queue = queue()
        val events = mutableListOf<String>()
        queue.saving(null, { it }) {
            queue.defer("outer") { fail("outer failed") }
            queue.saving(null, { it }) { queue.defer("inner") { events += "inner" }; true }
            false
        }
        assertEquals(listOf("inner"), events)
    }

}
