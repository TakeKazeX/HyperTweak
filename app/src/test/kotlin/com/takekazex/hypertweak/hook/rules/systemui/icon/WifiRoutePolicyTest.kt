package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.*
import org.junit.Test

class WifiRoutePolicyTest {
    @Test fun connectedWifiDisappearsWhenDefaultRouteMovesToCellularAndReturnsWithWifi() {
        assertFalse(WifiRoutePolicy.hide(true, true, false))
        assertTrue(WifiRoutePolicy.hide(true, false, false))
        assertFalse(WifiRoutePolicy.hide(true, true, false))
    }

    @Test fun nativeInactiveAndUnknownBranchesArePreserved() {
        assertFalse(WifiRoutePolicy.hide(false, false, true))
        assertFalse(WifiRoutePolicy.hide(true, null, false))
        assertTrue(WifiRoutePolicy.hide(true, null, true))
        assertTrue(WifiRoutePolicy.hide(true, true, true))
    }
}
