package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.presentation.icon.NetworkState
import com.takekazex.hypertweak.presentation.icon.NetworkTransport
import com.takekazex.hypertweak.presentation.icon.toDuoNetwork
import com.takekazex.hypertweak.presentation.icon.duo.DuoTransport
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayStateTest {
    @Test fun replayRetirementAndReplacementGeneration() {
        val queue = mutableListOf<() -> Unit>()
        val state = ReplayState(0, { queue += it }, { it() })
        val seen = mutableListOf<Int>()
        val old = state.subscribe { seen += state.value }
        old.close()
        state.publish(1)
        val current = state.subscribe { seen += state.value }
        queue.toList().forEach { it() }
        assertEquals(listOf(1), seen)
        state.publish(1)
        state.publish(2)
        assertEquals(listOf(1, 2), seen)
        current.close()
        state.publish(3)
        assertEquals(listOf(1, 2), seen)
    }

    @Test fun duoProjectionPreservesRouteAndWifiSemantics() {
        val input = NetworkState(NetworkTransport.VPN, true, 3, false, 7, 4)
        val duo = input.toDuoNetwork()
        assertEquals(DuoTransport.VPN, duo.transport)
        assertEquals(true, duo.validated)
        assertEquals(3, duo.wifiLevel)
        assertEquals(false, duo.wifiDefault)
    }
}
