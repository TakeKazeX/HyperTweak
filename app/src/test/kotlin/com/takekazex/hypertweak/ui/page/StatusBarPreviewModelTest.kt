package com.takekazex.hypertweak.ui.page

import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the preview's policy mapping: the composable only draws what this builder returns, so a
 * regression in ordering, hiding, left placement or the signal substitution is caught here rather
 * than only being visible on a device.
 */
class StatusBarPreviewModelTest {
    private fun input(
        position: Int = IconSlotPolicyConfig.POSITION_SYSTEM,
        customOrder: Set<String> = emptySet(),
        reorderHidden: Boolean = false,
        slotModes: Map<String, Int> = emptyMap(),
        extraHidden: Set<String> = emptySet(),
        leftSlots: Set<String> = emptySet(),
        stackedEnabled: Boolean = false,
        duoEnabled: Boolean = false,
        duoSizeDp: Float = 24f,
        hideMobileOnWifi: Boolean = false,
        hideWifiConnected: Boolean = false,
        showCellularType: Boolean = true
    ) = StatusBarPreviewInput(
        position = position,
        customOrder = customOrder,
        reorderHidden = reorderHidden,
        slotModes = slotModes,
        extraHidden = extraHidden,
        leftSlots = leftSlots,
        stackedEnabled = stackedEnabled,
        duoEnabled = duoEnabled,
        duoSizeDp = duoSizeDp,
        hideMobileOnWifi = hideMobileOnWifi,
        hideWifiConnected = hideWifiConnected,
        showCellularType = showCellularType
    )

    private fun allSlots(model: StatusBarPreviewModel) =
        model.leftSlots + model.indicatorSlots + model.coreSlots

    @Test
    fun hiddenModeRemovesTheSlot() {
        val model = buildStatusBarPreview(input(slotModes = mapOf("bluetooth" to 4)))
        assertFalse(allSlots(model).contains("bluetooth"))
    }

    @Test
    fun controlCenterOnlyAlsoLeavesTheStatusBarPreview() {
        val model = buildStatusBarPreview(input(slotModes = mapOf("location" to 3)))
        assertFalse(allSlots(model).contains("location"))
    }

    @Test
    fun leftToggleMovesTheSlotBesideTheClock() {
        val model = buildStatusBarPreview(input(leftSlots = setOf("bluetooth")))
        assertTrue(model.leftSlots.contains("bluetooth"))
        assertFalse(model.indicatorSlots.contains("bluetooth"))
        assertFalse(model.coreSlots.contains("bluetooth"))
    }

    @Test
    fun leftContainerStaysAtTwoIcons() {
        val model = buildStatusBarPreview(
            input(leftSlots = setOf("alarm_clock", "bluetooth", "location", "zen"))
        )
        assertEquals(PREVIEW_MAX_LEFT_SLOTS, model.leftSlots.size)
        // A left-placed slot the cap drops stays out of the right cluster too: the hook hides it
        // there, so drawing it on the right would be a lie.
        assertFalse(model.indicatorSlots.any { it in model.leftSlots })
    }

    @Test
    fun connectivityClusterIsPinnedSeparateFromIndicators() {
        val model = buildStatusBarPreview(input())
        assertTrue(model.coreSlots.contains("wifi"))
        assertTrue(model.coreSlots.contains("handle_battery"))
        assertFalse(model.indicatorSlots.contains("wifi"))
        assertTrue(model.indicatorSlots.contains("alarm_clock"))
    }

    @Test
    fun nativeDualSimDrawsBothBadgedSignalIcons() {
        val core = buildStatusBarPreview(input()).coreSlots
        assertTrue(core.contains("single_mobile_sim1"))
        assertTrue(core.contains("single_mobile_sim2"))
        assertFalse(core.contains("mobile"))
    }

    @Test
    fun signalClusterPrecedesWifiAndBattery() {
        val core = buildStatusBarPreview(input()).coreSlots
        val signal = core.indexOf("single_mobile_sim2")
        assertTrue(signal < core.indexOf("wifi"))
        assertTrue(core.indexOf("wifi") < core.indexOf("handle_battery"))
        // The ROM draws the battery in its own trailing container, so it is always last.
        assertEquals(core.last(), "handle_battery")
    }

    @Test
    fun wifiBeforeMobilePreferenceFrontsTheWifiGlyph() {
        val core = buildStatusBarPreview(
            input(position = IconSlotPolicyConfig.POSITION_WIFI_BEFORE_MOBILE)
        ).coreSlots
        assertEquals("wifi", core.first())
        assertEquals("handle_battery", core.last())
    }

    @Test
    fun hiddenMobileSlotHidesTheNativePair() {
        val core = buildStatusBarPreview(input(slotModes = mapOf("mobile" to 4))).coreSlots
        assertFalse(core.any { it.startsWith("single_mobile_sim") })
    }

    @Test
    fun duoFoldsTheCoreClusterIntoOneGlyph() {
        val model = buildStatusBarPreview(input(duoEnabled = true))
        val slots = allSlots(model)
        assertEquals(1, slots.count { it == PREVIEW_DUO_SLOT })
        assertFalse(slots.contains("wifi"))
        assertFalse(slots.contains("single_mobile_sim1"))
        assertFalse(slots.contains("handle_battery"))
        // Duo carries the network type inside its glyph, so no separate label draws.
        assertFalse(model.showNetworkType)
    }

    @Test
    fun stackedSignalReplacesTheNativePair() {
        val slots = allSlots(buildStatusBarPreview(input(stackedEnabled = true)))
        assertTrue(slots.contains("stacked_mobile_icon"))
        assertFalse(slots.contains("single_mobile_sim1"))
        assertFalse(slots.contains("single_mobile_sim2"))
    }

    @Test
    fun stackedSignalHonorsItsOwnSlotMode() {
        val slots = allSlots(
            buildStatusBarPreview(
                input(stackedEnabled = true, slotModes = mapOf("stacked_mobile_icon" to 4))
            )
        )
        assertFalse(slots.contains("stacked_mobile_icon"))
    }

    @Test
    fun wifiRulesAdjustTheCoreCluster() {
        assertFalse(allSlots(buildStatusBarPreview(input(hideWifiConnected = true))).contains("wifi"))
        assertFalse(
            allSlots(buildStatusBarPreview(input(hideMobileOnWifi = true)))
                .any { it.startsWith("single_mobile_sim") }
        )
    }

    @Test
    fun networkTypeLabelFollowsTheSettingAndTheSignal() {
        assertTrue(buildStatusBarPreview(input()).showNetworkType)
        assertFalse(buildStatusBarPreview(input(showCellularType = false)).showNetworkType)
        assertFalse(buildStatusBarPreview(input(hideMobileOnWifi = true)).showNetworkType)
    }

    @Test
    fun customOrderIsHonored() {
        val model = buildStatusBarPreview(
            input(
                position = IconSlotPolicyConfig.POSITION_CUSTOM,
                customOrder = setOf("0:alarm_clock")
            )
        )
        assertEquals("alarm_clock", allSlots(model).first())
    }

    @Test
    fun duoSizeIsClampedToTheHookRange() {
        assertEquals(32f, buildStatusBarPreview(input(duoSizeDp = 99f)).duoSizeDp, 0.0001f)
        assertEquals(16f, buildStatusBarPreview(input(duoSizeDp = 1f)).duoSizeDp, 0.0001f)
    }
}
