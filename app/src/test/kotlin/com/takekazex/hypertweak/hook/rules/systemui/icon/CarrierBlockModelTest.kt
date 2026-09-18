package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierBlockModelTest {
    @Test
    fun labelsAreIndependentAndKeepUnicode() {
        assertEquals("工作", CarrierBlockPolicy.badgeText(0, " 工作 "))
        assertEquals("🌐 私人", CarrierBlockPolicy.badgeText(1, "🌐 私人"))
        val labels = listOf("工作", "私人")
        val reordered = resolve(dualSim(activeData = 202).reduce(MobileSignalEvent.Subscriptions(listOf(202, 101))))
        assertEquals(listOf("工作", "私人"), reordered.map { CarrierBlockPolicy.badgeText(it.slot, labels[it.slot]) })
    }

    @Test
    fun blankLabelsUseSlotDefaultsAndNeverContainNewlines() {
        assertEquals("1", CarrierBlockPolicy.badgeText(0, " \n\t"))
        assertEquals("2", CarrierBlockPolicy.badgeText(1, "\u0000"))
        assertEquals("SIM 工作", CarrierBlockPolicy.badgeText(0, "SIM\n工作\u0000"))
        assertEquals("1", CarrierBlockPolicy.badgeText(0, ""))
    }

    @Test
    fun longBadgeLeavesRoomForCarrierAndDisabledBadgeCostsNothing() {
        val width = CarrierBlockPolicy.badgeWidth(220, 200, 4, 52)
        assertEquals(73, width)
        assertEquals(143, CarrierBlockPolicy.textWidth(220, width + 4))
        assertEquals(11, CarrierBlockPolicy.badgeWidth(220, 11, 4, 52))
        assertEquals(0, CarrierBlockPolicy.badgeWidth(40, 60, 4, 52))
        assertEquals(0, CarrierBlockPolicy.badgeWidth(220, 0, 4, 52))
        assertEquals(220, CarrierBlockPolicy.textWidth(220, 0))
    }

    @Test
    fun iconHeightUsesHostPixelsWhileTypeTextCanGrowWithFontScale() {
        assertEquals(60, CarrierBlockPolicy.iconHeight(60, 3f))
        assertEquals(60, CarrierBlockPolicy.iconHeight(null, 3f))
        assertEquals(40, CarrierBlockPolicy.iconHeight(0, 2f))
        assertEquals(60, CarrierBlockPolicy.typeHeight(60, 3f, 1f))
        assertEquals(120, CarrierBlockPolicy.typeHeight(60, 3f, 2f))
    }

    /** 卡一 = slot 0, 卡二 = slot 1; the test doubles for `SubscriptionManager.getSlotIndex`. */
    private val slots = mapOf(101 to 0, 202 to 1)
    private val slotOf: (Int) -> Int = { subId -> slots[subId] ?: CarrierBlockPolicy.INVALID_SLOT }

    private fun dualSim(
        firstLevel: Int = 4,
        secondLevel: Int = 2,
        activeData: Int = 101,
        firstType: String? = "5G",
        secondType: String? = "4G",
        firstService: Boolean = true,
        secondService: Boolean = true
    ): MobileSignalState = MobileSignalState()
        .reduce(MobileSignalEvent.Subscriptions(listOf(101, 202)))
        .reduce(MobileSignalEvent.SignalModel(101, MobileSignalModel.cellular(firstLevel, 5)))
        .reduce(MobileSignalEvent.SignalModel(202, MobileSignalModel.cellular(secondLevel, 5)))
        .reduce(MobileSignalEvent.InService(101, firstService))
        .reduce(MobileSignalEvent.InService(202, secondService))
        .reduce(MobileSignalEvent.NetworkType(101, firstType))
        .reduce(MobileSignalEvent.NetworkType(202, secondType))
        .reduce(MobileSignalEvent.ActiveDataSubId(activeData))

    private fun resolve(
        state: MobileSignalState,
        wifiLevel: Int? = null,
        showNonDataType: Boolean = false,
        keepTypeOnWifi: Boolean = false
    ) = CarrierBlockPolicy.resolve(
        state = state,
        wifiLevel = wifiLevel,
        config = CarrierBlockConfig(
            showNonDataType = showNonDataType,
            keepTypeOnWifi = keepTypeOnWifi
        ),
        slotOf = slotOf
    )

    @Test
    fun rowsFollowPhysicalSlotsNotSubscriptionFlowOrder() {
        // The host reorders subscriptionOrder when the user swaps the default data SIM.
        val state = dualSim(activeData = 202)
        val reordered = state.reduce(MobileSignalEvent.Subscriptions(listOf(202, 101)))

        val rows = resolve(reordered)

        assertEquals(listOf(0, 1), rows.map { it.slot })
        assertEquals(listOf(101, 202), rows.map { it.subId })
        assertEquals(listOf(4, 2), rows.map { it.signalLevel })
    }

    @Test
    fun eachRowCarriesItsOwnSignalLevel() {
        val rows = resolve(dualSim(firstLevel = 5, secondLevel = 1))

        assertEquals(4, rows[0].signalLevel)
        assertEquals(1, rows[1].signalLevel)
        // Only the default data line shows its type until 开关 4 is on.
        assertEquals(listOf("5G", null), rows.map { it.typeText })
    }

    @Test
    fun missingSubscriptionHidesItsRow() {
        val single = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(202)))
            .reduce(MobileSignalEvent.SignalModel(202, MobileSignalModel.cellular(3, 5)))
            .reduce(MobileSignalEvent.InService(202, true))
            .reduce(MobileSignalEvent.ActiveDataSubId(202))

        val rows = resolve(single)

        assertFalse(rows[0].visible)
        assertNull(rows[0].signalLevel)
        assertTrue(rows[1].visible)
        assertEquals(202, rows[1].subId)
    }

    @Test
    fun noServiceUsesTheErrorLevelInsteadOfZeroBars() {
        val state = dualSim(firstService = false, secondService = true)

        val rows = resolve(state)

        assertEquals(-1, rows[0].signalLevel)
        assertEquals(2, rows[1].signalLevel)
    }

    @Test
    fun unknownModelNeverInventsBars() {
        val state = MobileSignalState()
            .reduce(MobileSignalEvent.Subscriptions(listOf(101)))
            .reduce(MobileSignalEvent.ActiveDataSubId(101))

        assertNull(resolve(state)[0].signalLevel)
    }

    @Test
    fun typeFollowsTheActiveDataSimOnly() {
        val rows = resolve(dualSim(activeData = 202), showNonDataType = false)

        assertNull(rows[0].typeText)
        assertEquals("4G", rows[1].typeText)
    }

    @Test
    fun switchFourAddsTheNonDataType() {
        val rows = resolve(dualSim(activeData = 202), showNonDataType = true)

        assertEquals("5G", rows[0].typeText)
        assertEquals("4G", rows[1].typeText)
    }

    @Test
    fun outOfServiceSimNeverShowsAType() {
        val rows = resolve(dualSim(firstService = false), showNonDataType = true)

        assertNull(rows[0].typeText)
    }

    @Test
    fun wifiBelongsToTheFirstRowOnly() {
        val state = dualSim().reduce(MobileSignalEvent.WifiAvailable(101, true))

        val rows = resolve(state, wifiLevel = 3)

        assertEquals(3, rows[0].wifiLevel)
        assertNull(rows[1].wifiLevel)
    }

    @Test
    fun wifiIsNotDrawnWhenCellularIsTheDefaultNetwork() {
        val rows = resolve(dualSim(), wifiLevel = 3)

        assertNull(rows[0].wifiLevel)
    }

    @Test
    fun wifiAndDataTypeCanShareTheFirstRow() {
        val state = dualSim(activeData = 101).reduce(MobileSignalEvent.WifiAvailable(101, true))

        val rows = resolve(state, wifiLevel = 4)

        assertEquals(4, rows[0].wifiLevel)
        // The type keeps its text and its box (so the name's budget and the Wi-Fi glyph never move)
        // but is suppressed unless 连接 WiFi 时依旧显示蜂窝类型 is on.
        assertEquals("5G", rows[0].typeText)
        assertTrue(rows[0].typeSuppressed)

        val kept = resolve(state, wifiLevel = 4, keepTypeOnWifi = true)
        assertEquals("5G", kept[0].typeText)
        assertFalse(kept[0].typeSuppressed)
    }

    @Test
    fun cellularDefaultNeverSuppressesTheType() {
        val rows = resolve(dualSim(activeData = 101))

        assertEquals("5G", rows[0].typeText)
        assertFalse(rows[0].typeSuppressed)
    }

    @Test
    fun wifiLevelIsClampedAndMissingLevelsStayHidden() {
        val state = dualSim().reduce(MobileSignalEvent.WifiAvailable(101, true))

        assertEquals(4, resolve(state, wifiLevel = 9)[0].wifiLevel)
        assertEquals(0, resolve(state, wifiLevel = -3)[0].wifiLevel)
        assertNull(resolve(state, wifiLevel = null)[0].wifiLevel)
    }

    @Test
    fun emptyStateHidesEveryRow() {
        val rows = resolve(MobileSignalState())

        assertEquals(2, rows.size)
        assertTrue(rows.none { it.visible })
        assertFalse(CarrierBlockPolicy.replacesStatusSignal(rows))
    }

    @Test
    fun fullRowBudgetDoesNotSubtractIconsFromTheShorterCarrierName() {
        // The host gave the first name its natural width (36px), then the old hook removed
        // signal + Wi-Fi + type (94px), leaving zero. Vertical rows share the whole 220px.
        assertEquals(126, CarrierBlockPolicy.textWidth(220, 94))
        assertEquals(190, CarrierBlockPolicy.textWidth(220, 30))
    }

    @Test
    fun narrowAndLargeFontBudgetsNeverOverflowOrTurnNegative() {
        assertEquals(40, CarrierBlockPolicy.textWidth(180, 140))
        assertEquals(0, CarrierBlockPolicy.textWidth(100, 140))
        assertEquals(0, CarrierBlockPolicy.textWidth(-1, 5))
        assertEquals(Int.MAX_VALUE, CarrierBlockPolicy.textWidth(Int.MAX_VALUE, -1))
    }

    @Test
    fun incompleteSecondSimCannotMaskTheEntireNativeCellularSlot() {
        val partial = dualSim().reduce(MobileSignalEvent.NonTerrestrial(202, true))
        assertFalse(CarrierBlockPolicy.replacesStatusSignal(resolve(partial)))
        assertTrue(CarrierBlockPolicy.replacesStatusSignal(resolve(dualSim())))
        val single = dualSim().reduce(MobileSignalEvent.Subscriptions(listOf(202)))
        assertTrue(CarrierBlockPolicy.replacesStatusSignal(resolve(single)))
    }

    @Test
    fun unmappableSubscriptionCannotBeSilentlyMasked() {
        val state = dualSim()
        val rows = CarrierBlockPolicy.resolve(state, null, CarrierBlockConfig()) { sub ->
            if (sub == 101) 0 else CarrierBlockPolicy.INVALID_SLOT
        }
        assertFalse(CarrierBlockPolicy.replacesStatusSignal(rows, state.subscriptionOrder))
    }

    @Test
    fun airplaneModeDoesNotKeepStaleCellularBarsOrTypes() {
        val airplane = dualSim().reduce(MobileSignalEvent.AirplaneMode(true))
        val rows = resolve(airplane, showNonDataType = true)
        assertTrue(rows.all { it.visible })
        assertTrue(rows.all { it.signalLevel == null && it.typeText == null })
        assertFalse(CarrierBlockPolicy.replacesStatusSignal(rows))
    }

    @Test
    fun onlyAnOwnedCompactRowOverridesCarrierNameHiding() {
        assertTrue(CarrierBlockPolicy.preserveCarrierName(true, true))
        assertFalse(CarrierBlockPolicy.preserveCarrierName(false, true))
        assertTrue(CarrierBlockPolicy.preserveCarrierName(false, false))
    }

    @Test
    fun missingWifiArtworkCanReleaseWifiWithoutReleasingCellular() {
        val both = CarrierMask(cellular = true, wifi = true).slots()
        assertTrue("wifi" in both && "mobile" in both && "stacked_mobile_icon" in both)
        val cellularOnly = CarrierMask(cellular = true).slots()
        assertFalse("wifi" in cellularOnly)
        assertTrue("mobile" in cellularOnly)
        val wifiOnly = CarrierMask(wifi = true).slots()
        assertEquals(setOf("wifi", "demo_wifi"), wifiOnly)
        assertFalse(CarrierMask().active)
        assertTrue(CarrierMask().slots().isEmpty())
    }

    @Test
    fun handoverFollowsTheHostProgressAndHandsOverInTheLastQuarter() {
        assertEquals(0f, CarrierHandover.travel(0f), 0f)
        assertEquals(0.5f, CarrierHandover.travel(0.5f), 0f)
        assertEquals(1f, CarrierHandover.travel(2f), 0f)

        assertEquals(1f, CarrierHandover.overlayFraction(0.5f), 0f)
        assertEquals(0f, CarrierHandover.overlayFraction(1f), 0f)
        assertEquals(0f, CarrierHandover.destinationAlpha(0.5f), 0f)
        assertEquals(1f, CarrierHandover.destinationAlpha(1f), 0f)
        assertTrue(CarrierHandover.handedOver(0.5f))
        assertFalse(CarrierHandover.handedOver(0f))
        assertFalse(CarrierHandover.handedOver(1f))
    }
}
