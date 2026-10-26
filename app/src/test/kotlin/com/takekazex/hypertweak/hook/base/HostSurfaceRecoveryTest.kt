package com.takekazex.hypertweak.hook.base

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test

class HostSurfaceRecoveryTest {
    private class Owner(events: MutableList<String>, name: String, fail: Boolean = false) : StaticHooker() {
        init {
            hostRecovery(HostRecoveryPhase.WINDOWS) {
                events += "$name:windows"
                if (fail) error("one host is unavailable")
            }
            hostRecovery(HostRecoveryPhase.ADAPTER, icons = true) { events += "$name:adapter" }
            hostRecovery(HostRecoveryPhase.SLOT_ORDER, icons = true) {
                if (fail) error("reject native slot transaction")
            }
        }
    }

    private fun scope(phase: HostRecoveryPhase) = HostScope(ContextWrapper(null), null, emptyList(), phase)

    @Test fun `only selected owners recover and icons refresh excludes unrelated hosts`() {
        val events = mutableListOf<String>()
        Owner(events, "absent")
        val live = Owner(events, "live")
        live.recoverHostSurfaces(scope(HostRecoveryPhase.WINDOWS), iconsOnly = true)
        assertTrue(events.isEmpty())
        live.recoverHostSurfaces(scope(HostRecoveryPhase.ADAPTER), iconsOnly = true)
        assertEquals(listOf("live:adapter"), events)
        live.recoverHostSurfaces(scope(HostRecoveryPhase.WINDOWS), iconsOnly = false)
        assertEquals(listOf("live:adapter", "live:windows"), events)
    }

    @Test fun `a broken view recovery does not block peers while slot failure reaches rollback`() {
        val events = mutableListOf<String>()
        val bad = Owner(events, "bad", fail = true)
        val good = Owner(events, "good")
        listOf(bad, good).forEach { it.recoverHostSurfaces(scope(HostRecoveryPhase.WINDOWS), false) }
        assertEquals(listOf("bad:windows", "good:windows"), events)
        assertThrows(IllegalStateException::class.java) {
            bad.recoverHostSurfaces(scope(HostRecoveryPhase.SLOT_ORDER), true)
        }
    }
}
