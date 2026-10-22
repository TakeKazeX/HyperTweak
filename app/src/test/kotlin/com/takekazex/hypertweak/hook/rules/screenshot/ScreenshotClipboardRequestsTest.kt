package com.takekazex.hypertweak.hook.rules.screenshot

import org.junit.Assert.*
import org.junit.Test

class ScreenshotClipboardRequestsTest {
    @Test fun `on and off each override only the first gate read in a publication`() {
        val requests = ScreenshotClipboardRequests()
        assertNull(requests.consume())
        requests.enter(true)
        assertEquals(true, requests.consume())
        assertNull("native notification checks keep their original gate", requests.consume())
        requests.leave()
        assertNull(requests.consume())
        requests.enter(false)
        assertEquals("disabled is an explicit decision even when native copying is on", false, requests.consume())
        requests.leave()
    }

    @Test fun `nested publication and failure cleanup do not leak requests into later work`() {
        val requests = ScreenshotClipboardRequests()
        requests.enter(true)
        try {
            requests.enter(false)
            try { assertEquals(false, requests.consume()) } finally { requests.leave() }
            assertEquals(true, requests.consume())
        } finally { requests.leave() }
        requests.enter(false)
        assertEquals("disabled is an explicit decision even when native copying is on", false, requests.consume())
        requests.leave()
        assertNull(requests.consume())
    }

    @Test fun `another thread cannot consume the saving thread request`() {
        val requests = ScreenshotClipboardRequests()
        requests.enter(true)
        val other = java.util.concurrent.Executors.newSingleThreadExecutor()
        try { assertNull(other.submit<Boolean?> { requests.consume() }.get()) }
        finally { other.shutdownNow() }
        assertEquals(true, requests.consume())
        requests.leave()
    }
}
