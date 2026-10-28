package com.takekazex.hypertweak.dock

import org.junit.Assert.*
import org.junit.Test

class DockCaptureSessionTest {
    private class Access : DockCaptureAccess {
        var current: Any? = Any()
        val registrations = mutableSetOf<Any>()
        val releases = mutableListOf<Any>()
        var starts = 0
        var reject = false
        override fun currentRoot() = current
        override fun registered(root: Any) = root in registrations
        override fun register(root: Any) {
            starts++
            check(!reject) { "producer unavailable" }
            registrations += root
        }
        override fun timestamp(root: Any) = if (root in registrations) 42L else 0L
        override fun release(root: Any) { registrations -= root; releases += root }
    }

    @Test fun `activation is explicit and repeated probes retain one registration`() {
        val access = Access()
        val session = DockCaptureSession(access)
        assertEquals(0L, session.textureTimestamp())
        assertEquals(0, access.starts)
        session.request()
        repeat(3) { assertEquals(42L, session.textureTimestamp()) }
        assertEquals(1, access.starts)
    }

    @Test fun `platform clearing registration recovers without recreating the renderer`() {
        val access = Access()
        var frameInvalidations = 0
        val session = DockCaptureSession(access) { frameInvalidations++ }
        session.request()
        session.reconcile()
        access.registrations.clear()
        assertEquals(42L, session.textureTimestamp())
        assertEquals(2, access.starts)
        assertEquals(2, frameInvalidations)
    }

    @Test fun `root replacement and detachment release the exact old owner`() {
        val access = Access()
        val session = DockCaptureSession(access)
        session.request()
        session.reconcile()
        val old = access.current!!
        access.current = Any()
        session.reconcile()
        assertEquals(listOf(old), access.releases)
        val replacement = access.current!!
        access.current = null
        assertEquals(0L, session.textureTimestamp())
        assertEquals(listOf(old, replacement), access.releases)
        assertTrue(access.registrations.isEmpty())
    }

    @Test fun `failed registration retries and retirement prevents late probes resurrecting capture`() {
        val access = Access()
        val session = DockCaptureSession(access)
        session.request()
        access.reject = true
        assertEquals(0L, session.textureTimestamp())
        access.reject = false
        assertEquals(42L, session.textureTimestamp())
        session.close()
        session.close()
        assertEquals(0L, session.textureTimestamp())
        assertEquals(2, access.starts)
        assertEquals(1, access.releases.size)
    }
}
