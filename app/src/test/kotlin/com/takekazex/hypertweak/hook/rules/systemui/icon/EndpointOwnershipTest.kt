package com.takekazex.hypertweak.hook.rules.systemui.icon


import org.junit.Assert.*
import org.junit.Test

class EndpointOwnershipTest {
    private data class EqualObject(val id: Int)

    @Test fun targetsAndOwnersUseIdentityAndAcquisitionIsIdempotent() {
        val leases = EndpointOwnership<EqualObject>()
        val target = EqualObject(1)
        val equalTarget = EqualObject(1)
        val owner = EqualObject(2)
        val equalOwner = EqualObject(2)
        assertTrue(leases.acquire(target, owner))
        assertTrue(leases.acquire(target, owner))
        assertFalse(leases.acquire(target, equalOwner))
        assertFalse(leases.release(target, equalOwner))
        assertFalse(equalTarget in leases)
        assertTrue(leases.acquire(equalTarget, equalOwner))
        assertTrue(leases.release(target, owner))
        assertTrue(equalTarget in leases)
    }

    @Test fun retiredHostsAndOldOwnersCannotReleaseNewBindings() {
        val leases = EndpointOwnership<Any>()
        val target = Any(); val old = Any(); val current = Any()
        assertTrue(leases.acquire(target, old))
        leases.retire(target)
        assertTrue(leases.acquire(target, current))
        assertFalse(leases.release(target, old))
        assertTrue(leases.owns(target, current))
        leases.clear()
        assertFalse(target in leases)
        assertFalse(leases.release(target, current))
    }

    @Test fun retiringAnOldShadeSubscriberPreservesTheReplacement() {
        val events = HandoverPresence()
        val old = Any(); val current = Any(); val observed = mutableListOf<String>()
        events.bind(old, { observed += "old" }, { observed += "old:$it" })
        events.started()
        events.bind(current, { observed += "current" }, { observed += "current:$it" })
        events.retire(old)
        events.started(); events.finished(true)
        events.retire(current)
        events.started(); events.finished(false)
        assertEquals(listOf("old", "current", "current:true"), observed)
    }

    @Test fun activeProxyQueriesFollowTheCurrentOwnerAndRetirement() {
        val events = HandoverPresence()
        val root = Any(); val other = Any(); val old = Any(); val current = Any()
        assertFalse(events.hasActiveProxy(root))
        events.bind(old, {}, {}, { it === root })
        assertTrue(events.hasActiveProxy(root))
        assertFalse(events.hasActiveProxy(other))
        events.bind(current, {}, {}, { it === other })
        events.retire(old)
        assertFalse(events.hasActiveProxy(root))
        assertTrue(events.hasActiveProxy(other))
        events.retire(current)
        assertFalse(events.hasActiveProxy(other))
    }

    @Test fun handoverAndAirplaneQueriesRetireWithTheirRenderer() {
        val events = HandoverPresence()
        val owner = Any(); val replacement = Any(); val root = Any()
        var owns = true
        events.bind(owner, {}, {}, ownsAirplane = { it === root }, ownsSignalHandover = { owns })
        assertTrue(events.ownsSignalHandover())
        assertTrue(events.ownsAirplane(root))
        owns = false
        assertFalse(events.ownsSignalHandover())
        events.bind(replacement, {}, {})
        events.retire(owner)
        assertFalse(events.ownsAirplane(root))
        events.retire(replacement)
        assertFalse(events.ownsSignalHandover())
    }
}
