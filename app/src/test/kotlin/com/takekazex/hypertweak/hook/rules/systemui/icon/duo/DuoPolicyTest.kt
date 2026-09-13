package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalEvent
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalModel
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalState
import org.junit.Assert.*
import org.junit.Test

class DuoPolicyTest {
    private val battery = DuoBattery(60, false, false)
    private val cellular = DuoNetwork(DuoTransport.CELLULAR, true)
    private fun mobile(): MobileSignalState = MobileSignalState()
        .reduce(MobileSignalEvent.Subscriptions(listOf(11, 22)))
        .reduce(MobileSignalEvent.SignalModel(11, MobileSignalModel.cellular(4, 5)))
        .reduce(MobileSignalEvent.SignalModel(22, MobileSignalModel.cellular(1, 5)))
        .reduce(MobileSignalEvent.InService(11, true))
        .reduce(MobileSignalEvent.InService(22, true))
        .reduce(MobileSignalEvent.NetworkType(11, "5G-A"))
        .reduce(MobileSignalEvent.NetworkType(22, "4G"))
        .reduce(MobileSignalEvent.ActiveDataSubId(11))

    @Test fun switchingDataSimUpdatesBothDotsAndNetworkLabel() {
        assertEquals(4, DuoPolicy.content(battery, mobile(), cellular)?.mobileLevel)
        val switched = mobile().reduce(MobileSignalEvent.ActiveDataSubId(22))
        val result = DuoPolicy.content(battery, switched, cellular)!!
        assertEquals(1, result.mobileLevel)
        assertEquals("4G", result.networkLabel)
        assertEquals(listOf(11, 22), switched.subscriptionOrder)
    }

    @Test fun removedOrUnknownDataSimCannotShowTheLastKnownSim() {
        assertNull(DuoPolicy.content(battery, mobile().reduce(MobileSignalEvent.Subscriptions(listOf(22))), cellular))
        assertNull(DuoPolicy.content(battery, mobile().reduce(MobileSignalEvent.ActiveDataSubId(999)), cellular))
    }

    @Test fun hostWifiLevelWinsOverTheDefaultTransport() {
        // A VPN hides the Wi-Fi transport from the default-network callback, so a non-null host
        // Wi-Fi level is the authoritative signal for showing the Wi-Fi layer.
        val result = DuoPolicy.content(battery, mobile(), cellular.copy(wifiLevel = 4))!!
        assertEquals(4, result.wifiLevel)
        assertNull(result.networkLabel)
        assertEquals(4, result.mobileLevel)
    }

    @Test fun unvalidatedWifiStaysWifiAndKeepsMainDataSimSignal() {
        val result = DuoPolicy.content(battery, mobile(), DuoNetwork(DuoTransport.WIFI, false, 2))!!
        assertEquals(2, result.wifiLevel)
        assertNull(result.networkLabel)
        assertTrue(result.noInternet)
        assertEquals(4, result.mobileLevel)
    }

    @Test fun unknownWifiOrDefaultTransportRestoresNativeInsteadOfGuessing() {
        // Wi-Fi transport without a host level means the host model is not ready yet.
        assertNull(DuoPolicy.content(battery, mobile(), DuoNetwork(DuoTransport.WIFI, true)))
        // A known host Wi-Fi level still renders, whatever the default transport reports.
        for (type in listOf(DuoTransport.NONE, DuoTransport.UNKNOWN, DuoTransport.OTHER)) {
            assertEquals(4, DuoPolicy.content(battery, mobile(), DuoNetwork(type, true, 4))?.wifiLevel)
        }
    }

    @Test fun vpnUsesHostDataStateAndKeepsNoServiceSupported() {
        val connectedMobile = mobile()
            .reduce(MobileSignalEvent.InService(11, true))
            .reduce(MobileSignalEvent.DataConnected(11, true))
        val connected = DuoPolicy.content(battery, connectedMobile, DuoNetwork(DuoTransport.VPN, true))!!
        assertEquals("5G-A", connected.networkLabel)
        assertFalse(connected.noInternet)

        // Without a host data connection the VPN path cannot confirm cellular, so fall back.
        val idle = mobile().reduce(MobileSignalEvent.DataConnected(11, false))
        assertNull(DuoPolicy.content(battery, idle, DuoNetwork(DuoTransport.VPN, true))?.networkLabel)

        // No default data network is a supported Duo state, not a fallback.
        val offline = mobile().reduce(MobileSignalEvent.InService(11, false))
        val noService = DuoPolicy.content(battery, offline, DuoNetwork(DuoTransport.NONE, false))!!
        assertTrue(noService.noService)
        assertFalse(noService.noInternet)
    }

    @Test fun noServiceAndZeroBarsAreDifferent() {
        val zero = mobile().reduce(MobileSignalEvent.SignalModel(11, MobileSignalModel.cellular(0, 5)))
        assertFalse(DuoPolicy.content(battery, zero, cellular)!!.noService)
        val disconnected = zero.reduce(MobileSignalEvent.InService(11, false))
        assertTrue(DuoPolicy.content(battery, disconnected, DuoNetwork(DuoTransport.WIFI, true, 4))!!.noService)
    }

    @Test fun airplaneKeepsDuoWhileSatelliteAndUnknownBatteryFallBack() {
        // Airplane mode is a supported Duo state: the glyph degrades to "no bars, no internet"
        // instead of dropping back to the native icons.
        val airplane = DuoPolicy.content(battery, mobile().reduce(MobileSignalEvent.AirplaneMode(true)), cellular)!!
        assertTrue(airplane.airplaneMode)
        assertEquals(0, airplane.mobileLevel)
        assertFalse(airplane.noService)
        assertFalse(airplane.noInternet)
        assertNull(DuoPolicy.content(battery, mobile().reduce(MobileSignalEvent.NonTerrestrial(11, true)), cellular))
        assertNull(DuoPolicy.content(null, mobile(), cellular))
        assertNull(DuoPolicy.content(battery.copy(percent = 101), mobile(), cellular))
    }

    @Test fun batteryPriorityAndHostLowThreshold() {
        assertEquals(BatteryTone.CHARGING, DuoBattery(10, true, true).tone)
        assertEquals(BatteryTone.POWER_SAVE, DuoBattery(10, false, true).tone)
        assertEquals(BatteryTone.LOW, DuoBattery(19, false, false).tone)
        assertEquals(BatteryTone.NORMAL, DuoBattery(20, false, false).tone)
    }

    @Test fun restoreExpandedLeavesHomeReplacementAndUnsupportedSurfacesAlone() {
        assertTrue(DuoPolicy.replaces(DuoSurface.HOME, DuoExpandedStyle.RESTORE_NATIVE))
        assertFalse(DuoPolicy.replaces(DuoSurface.EXPANDED, DuoExpandedStyle.RESTORE_NATIVE))
        assertTrue(DuoPolicy.replaces(DuoSurface.EXPANDED, DuoExpandedStyle.KEEP_DUO))
        assertFalse(DuoPolicy.replaces(DuoSurface.UNSUPPORTED, DuoExpandedStyle.KEEP_DUO))
    }

    @Test fun hostTypeMissingOrUnboundedCannotBecomeAnInvented5GLabel() {
        for (label in listOf(null, "", "unknownnetworktype", "5G\n4G")) {
            assertNull(DuoPolicy.content(battery, mobile().reduce(MobileSignalEvent.NetworkType(11, label)), cellular))
        }
    }
}
