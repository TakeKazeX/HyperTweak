package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import org.junit.Assert.*
import org.junit.Test

class CarrierEndpointAlphasTest {
    private class Endpoint(var alpha: Float)
    private fun leases() = CarrierEndpointAlphas<Endpoint>({ it.alpha }, { target, value -> target.alpha = value })

    @Test fun nativeSourceStaysVisibleUntilOverlayDrawsAndReturnsAtEndpoints() {
        val leases = leases(); val source = Endpoint(.7f)
        leases.suppress(source, false)
        assertEquals(.7f, source.alpha, 0f)
        leases.suppress(source, true)
        assertEquals(0f, source.alpha, 0f)
        leases.suppress(source, true)
        leases.restore()
        assertEquals(.7f, source.alpha, 0f)
        leases.restore()
        assertEquals(.7f, source.alpha, 0f)
    }

    @Test fun hostFadeDuringBorrowBecomesTheNewRestoreValue() {
        val leases = leases(); val source = Endpoint(1f)
        leases.suppress(source, true)
        source.alpha = .4f
        leases.suppress(source, true)
        assertEquals(0f, source.alpha, 0f)
        leases.suppress(source, false)
        assertEquals(.4f, source.alpha, 0f)
        leases.restore()
        assertEquals(.4f, source.alpha, 0f)
    }

    @Test fun retirementDoesNotOverwriteANewerHostWrite() {
        val leases = leases(); val source = Endpoint(.8f)
        leases.suppress(source, true)
        source.alpha = .3f
        leases.restore()
        assertEquals(.3f, source.alpha, 0f)
        leases.suppress(source, true)
        leases.restore()
        assertEquals(.3f, source.alpha, 0f)
    }

    @Test fun pruningOneSourceRetainsTheOtherUntilDisable() {
        val leases = leases(); val wifi = Endpoint(.6f); val type = Endpoint(.9f)
        leases.suppress(wifi, true); leases.suppress(type, true)
        leases.restore(setOf(wifi))
        assertEquals(0f, wifi.alpha, 0f)
        assertEquals(.9f, type.alpha, 0f)
        leases.restore()
        assertEquals(.6f, wifi.alpha, 0f)
        assertEquals(.9f, type.alpha, 0f)
    }
}
