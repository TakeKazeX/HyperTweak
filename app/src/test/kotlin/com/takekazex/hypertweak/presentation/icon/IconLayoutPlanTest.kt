package com.takekazex.hypertweak.presentation.icon

import com.takekazex.hypertweak.settings.contract.IconSlotPolicyConfig
import com.takekazex.hypertweak.settings.contract.SignalStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class IconLayoutPlanTest {
    private val part = SlotPart.Slot("wifi")
    private val surface = PlanSurface.STATUS_BAR
    private val network = NetworkDisplayModel()

    @Test fun userHidingWinsOverReadyClaim() {
        val policy = IconSlotPolicyConfig(slotModes = mapOf("wifi" to 4))
        val claim = Claim(RendererId.DUO, surface, setOf(part), true, 10)
        val plan = IconLayoutPlan.plan(policy, SignalStyle.DUO, network, listOf(claim), listOf("wifi"))
        assertEquals(Ownership.Hidden("user"), plan[surface].owner(part))
    }

    @Test fun onlyReadyMatchingStyleClaimsCompeteByPriority() {
        val claims = listOf(
            Claim(RendererId.STACKED, surface, setOf(part), true, 100),
            Claim(RendererId.DUO, surface, setOf(part), false, 50),
            Claim(RendererId.CARRIER, surface, setOf(part), true, 5),
            Claim(RendererId.LEFT, surface, setOf(part), true, 5)
        )
        val plan = IconLayoutPlan.plan(IconSlotPolicyConfig(), SignalStyle.DUO, network, claims, listOf("wifi"))
        assertEquals(Ownership.Replaced(RendererId.CARRIER), plan[surface].owner(part))
        val noReady = IconLayoutPlan.plan(IconSlotPolicyConfig(), SignalStyle.NORMAL, network, claims.take(2), listOf("wifi"))
        assertEquals(Ownership.Native, noReady[surface].owner(part))
    }

    @Test fun mobileSlotHidingPreventsChildReplacement() {
        val mobile = MobileSignalState().reduce(MobileSignalEvent.Subscriptions(listOf(1)))
        val policy = IconSlotPolicyConfig(slotModes = mapOf("mobile" to 4))
        val visibility = NetworkIconVisibility(slotModes = policy.slotModes)
        val network = NetworkDisplayModel(mobile = mobile, visibility = visibility)
        val claim = Claim(RendererId.DUO, surface, setOf(SlotPart.MobileBars(1), SlotPart.TypeLabel(1)), true, 10)
        val result = IconLayoutPlan.plan(policy, SignalStyle.DUO, network, listOf(claim))[surface]
        assertEquals(Ownership.Hidden("user"), result.owner(SlotPart.MobileBars(1)))
        assertEquals(Ownership.Hidden("user"), result.owner(SlotPart.TypeLabel(1)))
    }

    @Test fun rowAndChildrenConflictAndUnknownSubscriptionsStayNative() {
        val mobile = MobileSignalState().reduce(MobileSignalEvent.Subscriptions(listOf(1)))
        val claims = listOf(
            Claim(RendererId.CARRIER, surface, setOf(SlotPart.MobileBars(1)), true, 20),
            Claim(RendererId.DUO, surface, setOf(SlotPart.MobileRow(1), SlotPart.MobileRow(99)), true, 10)
        )
        val result = IconLayoutPlan.plan(IconSlotPolicyConfig(), SignalStyle.DUO, NetworkDisplayModel(mobile), claims)[surface]
        assertEquals(Ownership.Replaced(RendererId.CARRIER), result.owner(SlotPart.MobileBars(1)))
        assertEquals(Ownership.Native, result.owner(SlotPart.MobileRow(1)))
        assertEquals(Ownership.Native, result.owner(SlotPart.MobileRow(99)))
    }

    @Test fun aodAndKeyguardDuoClaimsAreIndependentOfStatusBarStyle() {
        val claims = listOf(PlanSurface.KEYGUARD, PlanSurface.AOD).map {
            Claim(RendererId.DUO, it, setOf(SlotPart.Battery), true, 1)
        }
        val plan = IconLayoutPlan.plan(IconSlotPolicyConfig(), SignalStyle.NORMAL, network, claims)
        assertEquals(Ownership.Replaced(RendererId.DUO), plan[PlanSurface.KEYGUARD].owner(SlotPart.Battery))
        assertEquals(Ownership.Replaced(RendererId.DUO), plan[PlanSurface.AOD].owner(SlotPart.Battery))
    }
    @Test fun hiddenClaimOutsideCatalogStillReportsHidden() {
        val part = SlotPart.Slot("vendor_slot")
        val policy = IconSlotPolicyConfig(slotModes = mapOf("vendor_slot" to 4))
        val claim = Claim(RendererId.LEFT, surface, setOf(part), true, 1)
        val plan = IconLayoutPlan.plan(policy, SignalStyle.NORMAL, network, listOf(claim), emptyList())
        assertEquals(Ownership.Hidden("user"), plan[surface].owner(part))
    }

    @Test fun nativeMaskUsesTheSameDefaultNetworkAsThePlan() {
        val mobile = MobileSignalState().reduce(MobileSignalEvent.Subscriptions(listOf(1)))
        val network = NetworkDisplayModel(mobile = mobile, wifiDefault = true,
            visibility = NetworkIconVisibility(hideMobileOnWifi = true))
        assertEquals(setOf(1), network.nativeMask(stackedPublished = false).hidden)
    }

    @Test fun batterySlotAndBatteryPartShareOwnership() {
        val claims = listOf(
            Claim(RendererId.CARRIER, surface, setOf(SlotPart.Slot("handle_battery")), true, 20),
            Claim(RendererId.DUO, surface, setOf(SlotPart.Battery), true, 10)
        )
        val plan = IconLayoutPlan.plan(IconSlotPolicyConfig(), SignalStyle.DUO, network, claims)
        assertEquals(Ownership.Replaced(RendererId.CARRIER), plan[surface].owner(SlotPart.Battery))
        assertEquals(plan[surface].owner(SlotPart.Battery), plan[surface].owner(SlotPart.Slot("handle_battery")))
    }

}
