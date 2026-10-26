package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import org.junit.Assert.*
import org.junit.Test

class CarrierTargetLeasesTest {
    private class Target(var ready: Boolean = true, var usable: Boolean = true, var network: Boolean = true, var exists: Boolean = true)
    private class Host : CarrierTargetLeases.Host<Target, Target> {
        val effects = mutableListOf<String>()
        var geometryReads = 0
        var removeOnStop = false
        val leases = CarrierTargetLeases(this)
        override fun parts(target: Target) = target.takeIf { it.exists }
        override fun ready(target: Target, parts: Target) = parts.ready
        override fun usable(target: Target): Boolean { geometryReads++; return target.usable }
        override fun network(target: Target, parts: Target) = parts.network
        override fun cellularReady(parts: Target) = parts.ready
        override fun stopMotion(target: Target) { effects += "stop"; if (removeOnStop) target.exists = false }
        override fun fadeLease(target: Target, parts: Target, leased: Boolean) { effects += "fade:$leased" }
        override fun alpha(target: Target, value: Float) { effects += "alpha:$value" }
    }
    @Test fun overlayAndFadeAreAcquiredOnceInOrderAndForeignOwnersCannotRelease() {
        val host = Host(); val target = Target(); val owner = Any()
        assertTrue(host.leases.acquire(target, owner))
        assertTrue(host.leases.acquire(target, owner))
        assertFalse(host.leases.acquire(target, Any()))
        host.leases.release(target, Any())
        assertEquals(listOf("stop", "fade:true", "alpha:1.0"), host.effects)
        host.leases.release(target, owner)
        assertEquals("fade:false", host.effects.last())
        assertFalse(target in host.leases)
    }
    @Test fun unavailableSuppressedAndDetachedTargetsDoNotDisturbTheNativeRow() {
        val host = Host(); val owner = Any()
        listOf(Target(ready = false), Target(usable = false), Target(exists = false)).forEach {
            assertFalse(host.leases.acquire(it, owner))
        }
        assertTrue(host.effects.isEmpty())
    }
    @Test fun removedRowsAndSignalTargetsRestoreTheirCurrentReadiness() {
        val host = Host(); val target = Target(network = false); val owner = Any()
        assertTrue(host.leases.acquire(target, owner)); host.leases.release(target, owner)
        assertEquals("alpha:1.0", host.effects.last())
        assertTrue(host.leases.acquire(target, owner)); target.exists = false
        host.leases.release(target, owner)
        assertEquals("alpha:0.0", host.effects.last())
    }
    @Test fun retiredOwnerCannotClearAReplacementLease() {
        val host = Host(); val target = Target(); val old = Any(); val replacement = Any()
        host.leases.acquire(target, old); host.leases.retire(target)
        host.leases.acquire(target, replacement)
        val before = host.effects.toList()
        host.leases.release(target, old)
        assertEquals(before, host.effects)
        assertTrue(target in host.leases)
        host.leases.clear()
        host.leases.release(target, replacement)
        assertEquals(before, host.effects)
    }

    @Test fun unavailableRowsSkipGeometryAndMotionRemovalCannotSwitchTheCapturedRow() {
        val host = Host(); val owner = Any()
        assertFalse(host.leases.acquire(Target(ready = false), owner))
        assertEquals(0, host.geometryReads)
        host.removeOnStop = true
        assertTrue(host.leases.acquire(Target(), owner))
        assertEquals(listOf("stop", "fade:true", "alpha:1.0"), host.effects)
    }
}
