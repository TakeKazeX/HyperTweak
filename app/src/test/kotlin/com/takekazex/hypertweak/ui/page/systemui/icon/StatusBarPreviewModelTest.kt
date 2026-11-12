package com.takekazex.hypertweak.ui.page.systemui.icon

import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconSlotPolicyConfig
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
        useSeparateCellularType: Boolean = true,
        typeOrder: Int = 0,
        keepCellularTypeOnWifi: Boolean = true,
        hideSimOne: Boolean = false,
        rotationLockEnabled: Boolean = false
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
        useSeparateCellularType = useSeparateCellularType,
        typeOrder = typeOrder,
        keepCellularTypeOnWifi = keepCellularTypeOnWifi,
        hideSimOne = hideSimOne,
        rotationLockEnabled = rotationLockEnabled
    )

    private fun allSlots(model: StatusBarPreviewModel) =
        model.leftSlots + model.indicatorSlots + model.coreSlots

    @Test
    fun hiddenModeRemovesTheSlot() {
        val model = buildStatusBarPreview(input(slotModes = mapOf("bluetooth" to 4)))
        assertFalse(allSlots(model).contains("bluetooth"))
    }

    @Test
    fun explicitFollowOverridesExtraHiddenSlot() {
        val hidden = buildStatusBarPreview(input(extraHidden = setOf("bluetooth")))
        val explicitFollow = buildStatusBarPreview(input(slotModes = mapOf("bluetooth" to 0), extraHidden = setOf("bluetooth")))
        assertFalse(allSlots(hidden).contains("bluetooth"))
        assertTrue(allSlots(explicitFollow).contains("bluetooth"))
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
    fun userHidingWinsOverLeftPlacement() {
        val model = buildStatusBarPreview(input(leftSlots = setOf("bluetooth"), slotModes = mapOf("bluetooth" to 3)))
        assertFalse(allSlots(model).contains("bluetooth"))
    }

    @Test
    fun rotationLockDefaultsToControlCenterAndLeftPlacementBringsItToTheBar() {
        val rotation = IconSlotPolicy.SLOT_ROTATION_LOCK
        assertFalse(allSlots(buildStatusBarPreview(input(rotationLockEnabled = true))).contains(rotation))

        val left = buildStatusBarPreview(input(rotationLockEnabled = true, leftSlots = setOf(rotation)))
        assertTrue(left.leftSlots.contains(rotation))
        assertFalse(left.indicatorSlots.contains(rotation))

        // An explicit status-bar mode shows it on the right without left placement.
        val statusBar = buildStatusBarPreview(input(rotationLockEnabled = true, slotModes = mapOf(rotation to 2)))
        assertTrue(statusBar.indicatorSlots.contains(rotation))

        // An explicit control-center-only mode wins over left placement.
        val controlCenterOnly = buildStatusBarPreview(input(
            rotationLockEnabled = true, leftSlots = setOf(rotation), slotModes = mapOf(rotation to 3)
        ))
        assertFalse(allSlots(controlCenterOnly).contains(rotation))
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
        assertTrue(core.contains("preview_sim1"))
        assertTrue(core.contains("preview_sim2"))
        assertFalse(core.contains("mobile"))
    }

    @Test
    fun signalClusterPrecedesWifiAndBattery() {
        val core = buildStatusBarPreview(input()).coreSlots
        val signal = core.indexOf("preview_sim2")
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
        assertFalse(core.any { it.startsWith("preview_sim") })
    }

    @Test
    fun duoFoldsTheCoreClusterIntoOneGlyph() {
        val model = buildStatusBarPreview(input(duoEnabled = true))
        val slots = allSlots(model)
        assertEquals(1, slots.count { it == PREVIEW_DUO_SLOT })
        assertFalse(slots.contains("wifi"))
        assertFalse(slots.contains("preview_sim1"))
        assertFalse(slots.contains("handle_battery"))
        // Duo carries the network type inside its glyph, so no separate label draws.
        assertFalse(model.showNetworkType)
    }

    @Test
    fun stackedSignalReplacesTheNativePair() {
        val slots = allSlots(buildStatusBarPreview(input(stackedEnabled = true)))
        assertTrue(slots.contains("stacked_mobile_icon"))
        assertFalse(slots.contains("preview_sim1"))
        assertFalse(slots.contains("preview_sim2"))
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
    fun stackedPreviewHidesBarsAndNativeTypeWithTheMobileSlot() {
        val hiddenGroup = buildStatusBarPreview(input(stackedEnabled = true, slotModes = mapOf("mobile" to 3)))
        assertFalse("stacked_mobile_icon" in hiddenGroup.coreSlots)
        assertFalse(hiddenGroup.showNetworkType)
    }

    @Test
    fun stackedBarsUseTheNativeTypeLabelAfterThem() {
        // Stacked forces the separate label on and ignores the type-order option.
        val core = buildStatusBarPreview(input(
            stackedEnabled = true, useSeparateCellularType = false, typeOrder = 3
        )).coreSlots
        assertEquals(core.indexOf("stacked_mobile_icon") + 1, core.indexOf(PREVIEW_NATIVE_TYPE_SLOT))
    }

    @Test
    fun wifiRulesAdjustTheCoreCluster() {
        assertFalse(allSlots(buildStatusBarPreview(input(hideWifiConnected = true))).contains("wifi"))
        assertFalse(
            allSlots(buildStatusBarPreview(input(hideMobileOnWifi = true)))
                .any { it.startsWith("preview_sim") }
        )
    }

    @Test
    fun hiddenNetworkGlyphsDoNotRemoveTheDuoBatteryPreview() {
        val model = buildStatusBarPreview(input(
            duoEnabled = true, hideMobileOnWifi = true, hideWifiConnected = true
        ))

        assertTrue(PREVIEW_DUO_SLOT in model.coreSlots)
        assertEquals(4, model.duoContent?.wifiLevel)
        assertEquals(null, model.duoContent?.wifiLevelToDraw)
        assertTrue(model.duoContent!!.cellularSignalLevels.isEmpty())
    }

    @Test
    fun hidingTheSampleDataSimKeepsTheOtherSignalWithoutItsTypeLabel() {
        val model = buildStatusBarPreview(input(hideSimOne = true))
        assertTrue("preview_sim2" in model.coreSlots)
        assertFalse("preview_sim1" in model.coreSlots)
        assertFalse(model.showNetworkType)
    }

    @Test
    fun networkTypeLabelFollowsTheSettingAndTheSignal() {
        assertTrue(buildStatusBarPreview(input()).showNetworkType)
        assertFalse(buildStatusBarPreview(input(useSeparateCellularType = false)).showNetworkType)
        assertFalse(buildStatusBarPreview(input(hideMobileOnWifi = true)).showNetworkType)
    }

    @Test
    fun wifiTypeRetentionAffectsOnlySeparateText() {
        assertFalse(buildStatusBarPreview(input(keepCellularTypeOnWifi = false)).showNetworkType)
        assertFalse(buildStatusBarPreview(input(stackedEnabled = true, keepCellularTypeOnWifi = false)).showNetworkType)
        assertTrue(buildStatusBarPreview(input(stackedEnabled = true)).showNetworkType)
        assertEquals(null, buildStatusBarPreview(input(duoEnabled = true)).duoContent?.networkLabel)
    }

    @Test
    fun typeSwapMovesTheLabelWithinTheCellularGroup() {
        val normal = buildStatusBarPreview(input()).coreSlots
        val swapped = buildStatusBarPreview(input(typeOrder = 2)).coreSlots
        assertEquals(SLOT_SINGLE_SIM1, normal[normal.indexOf(PREVIEW_NATIVE_TYPE_SLOT) + 1])
        assertEquals(SLOT_SINGLE_SIM2, swapped[swapped.indexOf(PREVIEW_NATIVE_TYPE_SLOT) - 1])
        assertFalse(PREVIEW_NATIVE_TYPE_SLOT in buildStatusBarPreview(input(
            typeOrder = 2, hideSimOne = true
        )).coreSlots)
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
