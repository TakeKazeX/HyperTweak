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
        hideWifiConnected: Boolean = false
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
        hideWifiConnected = hideWifiConnected
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
        val model = buildStatusBarPreview(input(slotModes = mapOf("nfc" to 3)))
        assertFalse(allSlots(model).contains("nfc"))
    }

    @Test
    fun leftToggleMovesTheSlotBesideTheClock() {
        val model = buildStatusBarPreview(input(leftSlots = setOf("bluetooth")))
        assertTrue(model.leftSlots.contains("bluetooth"))
        assertFalse(model.indicatorSlots.contains("bluetooth"))
        assertFalse(model.coreSlots.contains("bluetooth"))
    }

    @Test
    fun connectivityClusterIsPinnedSeparateFromIndicators() {
        val model = buildStatusBarPreview(input())
        assertTrue(model.coreSlots.contains("mobile"))
        assertTrue(model.coreSlots.contains("wifi"))
        assertTrue(model.coreSlots.contains("handle_battery"))
        assertFalse(model.indicatorSlots.contains("wifi"))
        assertTrue(model.indicatorSlots.contains("network_speed"))
    }

    @Test
    fun duoFoldsTheCoreClusterIntoOneGlyph() {
        val slots = allSlots(buildStatusBarPreview(input(duoEnabled = true)))
        assertEquals(1, slots.count { it == PREVIEW_DUO_SLOT })
        assertFalse(slots.contains("wifi"))
        assertFalse(slots.contains("mobile"))
        assertFalse(slots.contains("handle_battery"))
    }

    @Test
    fun stackedSignalReplacesTheNativeMobileSlot() {
        val slots = allSlots(buildStatusBarPreview(input(stackedEnabled = true)))
        assertTrue(slots.contains("stacked_mobile_icon"))
        assertFalse(slots.contains("mobile"))
    }

    @Test
    fun wifiRulesAdjustTheCoreCluster() {
        assertFalse(allSlots(buildStatusBarPreview(input(hideWifiConnected = true))).contains("wifi"))
        assertFalse(allSlots(buildStatusBarPreview(input(hideMobileOnWifi = true))).contains("mobile"))
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
