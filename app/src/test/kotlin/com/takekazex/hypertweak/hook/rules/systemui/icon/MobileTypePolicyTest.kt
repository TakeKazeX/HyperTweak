package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileTypePolicyTest {
    private fun state(
        ids: List<Int>,
        active: Int,
        type: String?,
        connected: Boolean? = true,
        wifi: Boolean? = false,
        roaming: Boolean? = false
    ): MobileSignalState {
        var result = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(ids))
            .reduce(MobileSignalEvent.ActiveDataSubId(active))
        if (active in ids) {
            result = result
                .reduce(MobileSignalEvent.NetworkType(active, type))
                .reduce(MobileSignalEvent.DataConnected(active, connected == true))
                .reduce(MobileSignalEvent.WifiAvailable(active, wifi == true))
                .reduce(MobileSignalEvent.Roaming(active, roaming == true))
        }
        return result
    }

    @Test
    fun typeAlwaysComesFromActiveDataSubscription() {
        var state = state(listOf(10, 20), 20, "5G")
            .reduce(MobileSignalEvent.NetworkType(10, "4G"))
        val output = MobileTypePolicy.resolve(state, MobileTypeConfig())

        assertEquals("5G", output.text)
        assertEquals(listOf(10, 20), state.subscriptionOrder)

        state = state.reduce(MobileSignalEvent.ActiveDataSubId(999))
        assertEquals("", MobileTypePolicy.resolve(state, MobileTypeConfig()).text)
    }

    @Test
    fun disconnectAndWifiRulesOnlyHideWhenRequested() {
        val disconnected = state(listOf(1), 1, "LTE", connected = false, wifi = true)
        assertEquals("LTE", MobileTypePolicy.resolve(disconnected, MobileTypeConfig()).text)
        assertEquals(
            "",
            MobileTypePolicy.resolve(
                disconnected,
                MobileTypeConfig(hideWhenDisconnected = true)
            ).text
        )
        assertEquals(
            "",
            MobileTypePolicy.resolve(
                disconnected,
                MobileTypeConfig(hideWhenWifiAvailable = true)
            ).text
        )
    }

    @Test
    fun roamingPrefixNeverAppearsForBlankType() {
        val blank = state(listOf(1), 1, "", roaming = true)
        val output = MobileTypePolicy.resolve(blank, MobileTypeConfig(showRoamingPrefix = true))

        assertEquals("", output.text)
        assertFalse(output.isRoaming)
    }

    @Test
    fun roamingPrefixIsAddedOnceAndBadgesDependOnRowCount() {
        val one = state(listOf(1), 1, "5G", roaming = true)
        val two = state(listOf(1, 2), 1, "5G", roaming = true)
        val config = MobileTypeConfig(
            showRoamingPrefix = true,
            showSingleBadge = true,
            showStackedBadge = false
        )

        assertEquals("R5G", MobileTypePolicy.resolve(one, config).text)
        assertTrue(MobileTypePolicy.showInternalBadge(one, config))
        assertFalse(MobileTypePolicy.showInternalBadge(two, config))
    }
}
