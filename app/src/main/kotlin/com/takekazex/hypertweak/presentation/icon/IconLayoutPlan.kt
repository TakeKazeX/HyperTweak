package com.takekazex.hypertweak.presentation.icon

import com.takekazex.hypertweak.settings.contract.IconConfig
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconSlotPolicyConfig
import com.takekazex.hypertweak.settings.contract.IconSurface
import com.takekazex.hypertweak.settings.contract.IconTunerContract
import com.takekazex.hypertweak.settings.contract.SignalStyle
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

enum class RendererId { STACKED, DUO, CARRIER, LEFT, ROTATION_LOCK, AIRPLANE }

enum class PlanSurface { STATUS_BAR, KEYGUARD, CONTROL_CENTER_COLLAPSED, CONTROL_CENTER_EXPANDED, AOD }

/** A replaceable piece of a surface: a whole slot, or a part of one SIM's mobile row. */
sealed interface SlotPart {
    data class Slot(val name: String) : SlotPart
    data class MobileBars(val subId: Int) : SlotPart
    data class MobileRow(val subId: Int) : SlotPart
    data class TypeLabel(val subId: Int) : SlotPart
    data object Battery : SlotPart
}

/** A renderer's request to replace [replaces] on [surface]; only a [ready] claim takes effect. */
data class Claim(
    val owner: RendererId,
    val surface: PlanSurface,
    val replaces: Set<SlotPart>,
    val ready: Boolean,
    val priority: Int
)

sealed interface Ownership {
    data object Native : Ownership
    data class Hidden(val reason: String) : Ownership
    data class Replaced(val owner: RendererId) : Ownership
}

data class SurfaceLayout(
    val ownership: Map<SlotPart, Ownership>,
    val order: List<String>,
    val leftSlots: Set<String>,
    val upperRow: List<String>,
    val lowerRow: List<String>
) {
    fun owner(part: SlotPart): Ownership = ownership[canonical(part)] ?: Ownership.Native
}

data class LayoutResult(val surfaces: Map<PlanSurface, SurfaceLayout>) {
    operator fun get(surface: PlanSurface): SurfaceLayout = surfaces.getValue(surface)
}

private const val BATTERY_SLOT = "handle_battery"
private const val USER_HIDDEN = "user"

/** The battery is addressed by slot name in host lists and as [SlotPart.Battery] in claims. */
private fun canonical(part: SlotPart): SlotPart = if (part == SlotPart.Slot(BATTERY_SLOT)) SlotPart.Battery else part

/**
 * Decides who owns every slot and mobile-row part on every surface.
 *
 * Rules, in order:
 * 1. A slot the user hides stays hidden; no claim can bring it back.
 * 2. A ready claim replaces native parts, the highest priority first. A claim on a whole mobile
 *    row also covers its bars and type label, so two renderers never split one row.
 * 3. Everything else stays native.
 *
 * The runtime mask applier and the settings preview call the same function.
 */
internal object IconLayoutPlan {
    fun plan(
        config: IconConfig,
        network: NetworkDisplayModel,
        claims: List<Claim>,
        slots: List<String> = IconSlotPolicy.configurableSlots
    ): LayoutResult {
        val leftMode = config.slots.int(IconTunerContract.KEY_LEFT_MODE)
        val left = if (leftMode == IconTunerContract.LEFT_MODE_DISABLED) {
            emptySet()
        } else {
            IconTunerContract.leftPreferenceKeys
                .filter(config.slots::boolean)
                .flatMap(IconTunerContract::slotsForLeftPreference)
                .toSet()
        }
        val policy = IconSlotPolicyConfig(
            position = config.slots.int(IconTunerContract.KEY_POSITION),
            customOrderEntries = config.slots.value(IconTunerContract.KEY_POSITION_VALUES).stringSet(),
            reorderHidden = config.slots.boolean(IconTunerContract.KEY_POSITION_REORDER),
            slotModes = config.slots.slotModes,
            enabledModuleSlots = if (config.signalStyle == SignalStyle.STACKED) {
                IconSlotPolicy.STACKED_SLOTS.toSet()
            } else {
                emptySet()
            },
            leftSlots = left
        )
        val visibility = NetworkIconVisibility(
            hideMobileOnWifi = config.network.boolean(IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI),
            hideWifi = config.wifi.boolean(IconSettingsKeys.KEY_ICON_HIDE_WIFI_UNAVAILABLE),
            simOneMode = config.network.simOneVisibility,
            simTwoMode = config.network.simTwoVisibility,
            nonDefaultSimMode = config.network.nonDefaultSimVisibility,
            slotModes = policy.slotModes
        )
        val rowOverrides = config.controlCenter.value(SystemUiSettingsKeys.KEY_CC_ICON_ROW_OVERRIDES).stringSet()
        return plan(
            policy, config.signalStyle, network.copy(visibility = visibility), claims, slots, rowOverrides, leftMode
        )
    }

    fun plan(
        policy: IconSlotPolicyConfig,
        style: SignalStyle,
        network: NetworkDisplayModel,
        claims: List<Claim>,
        slots: List<String> = IconSlotPolicy.configurableSlots,
        rowOverrides: Set<String> = emptySet(),
        leftMode: Int = IconTunerContract.LEFT_MODE_HOME_AND_LOCKSCREEN
    ): LayoutResult {
        val order = IconSlotPolicy.normalizeOrder(slots, policy)
        val ranked = claims.filter { it.ready }
            .sortedWith(compareByDescending<Claim> { it.priority }.thenBy { it.owner.ordinal })
        val surfaces = PlanSurface.entries.associateWith { surface ->
            SurfacePlanner(surface, policy, style, network, leftMode).layout(order, ranked, rowOverrides)
        }
        return LayoutResult(surfaces)
    }
}

/** One surface's pass of [IconLayoutPlan]. */
private class SurfacePlanner(
    private val surface: PlanSurface,
    private val policy: IconSlotPolicyConfig,
    private val style: SignalStyle,
    private val network: NetworkDisplayModel,
    private val leftMode: Int
) {
    private val host = when (surface) {
        PlanSurface.CONTROL_CENTER_COLLAPSED, PlanSurface.CONTROL_CENTER_EXPANDED -> IconSurface.CONTROL_CENTER
        else -> IconSurface.STATUS_BAR
    }
    private val subscriptions = network.mobile.subscriptionOrder
    private val ownership = linkedMapOf<SlotPart, Ownership>()

    fun layout(order: List<String>, claims: List<Claim>, rowOverrides: Set<String>): SurfaceLayout {
        seedNative(order)
        claims.filter { it.surface == surface && allowed(it.owner) }.forEach(::apply)
        return SurfaceLayout(
            ownership = ownership,
            order = order,
            leftSlots = leftSlots(),
            upperRow = order.filter { IconSlotPolicy.controlCenterRow(it, rowOverrides) == 1 },
            lowerRow = order.filter { IconSlotPolicy.controlCenterRow(it, rowOverrides) == 2 }
        )
    }

    private fun seedNative(order: List<String>) {
        order.forEach { slot ->
            val part = canonical(SlotPart.Slot(slot))
            ownership[part] = if (userHides(part)) Ownership.Hidden(USER_HIDDEN) else Ownership.Native
        }
        val hiddenSims = if (blocks(IconSlotPolicy.SLOT_MOBILE)) {
            subscriptions.toSet()
        } else {
            network.hiddenCellularIds(surface)
        }
        subscriptions.forEach { id ->
            val owner = if (id in hiddenSims) Ownership.Hidden(USER_HIDDEN) else Ownership.Native
            ownership[SlotPart.MobileRow(id)] = owner
            ownership[SlotPart.MobileBars(id)] = owner
            ownership[SlotPart.TypeLabel(id)] = owner
        }
    }

    /** Stacked and Duo claims count only for the style that draws them; Duo always owns AOD rows. */
    private fun allowed(owner: RendererId): Boolean = when (owner) {
        RendererId.STACKED -> style == SignalStyle.STACKED && !blocks(IconSlotPolicy.SLOT_STACKED_SIGNAL) &&
            !nativeExpandedStacked
        RendererId.DUO -> style == SignalStyle.DUO || surface == PlanSurface.KEYGUARD || surface == PlanSurface.AOD
        else -> true
    }

    private fun apply(claim: Claim) {
        claim.replaces.forEach { requested ->
            val part = canonical(requested)
            if (userHides(part)) ownership[part] = Ownership.Hidden(USER_HIDDEN)
            val covered = covered(part)
            val parentReplaced = parentOf(part)?.let { ownership[it] is Ownership.Replaced } == true
            val free = covered.none { ownership[it] is Ownership.Hidden || ownership[it] is Ownership.Replaced }
            if (exists(part) && free && !parentReplaced) {
                covered.forEach { ownership[it] = Ownership.Replaced(claim.owner) }
            }
        }
    }

    private fun covered(part: SlotPart): Set<SlotPart> = when (part) {
        is SlotPart.MobileRow -> setOf(part, SlotPart.MobileBars(part.subId), SlotPart.TypeLabel(part.subId))
        else -> setOf(part)
    }

    private fun parentOf(part: SlotPart): SlotPart? = when (part) {
        is SlotPart.MobileBars -> SlotPart.MobileRow(part.subId)
        is SlotPart.TypeLabel -> SlotPart.MobileRow(part.subId)
        else -> null
    }

    /** A claim on a SIM that is gone, or on a slot the user hides, replaces nothing. */
    private fun exists(part: SlotPart): Boolean = when (part) {
        is SlotPart.MobileRow -> part.subId in subscriptions
        is SlotPart.MobileBars -> part.subId in subscriptions
        is SlotPart.TypeLabel -> part.subId in subscriptions
        is SlotPart.Slot, SlotPart.Battery -> !userHides(part)
    }

    private fun userHides(part: SlotPart): Boolean = when (part) {
        is SlotPart.Slot -> blocks(part.name) ||
            part.name == IconSlotPolicy.SLOT_STACKED_SIGNAL && nativeExpandedStacked ||
            part.name == IconSlotPolicy.SLOT_WIFI && network.hidesWifi(host)
        SlotPart.Battery -> blocks(BATTERY_SLOT)
        else -> false
    }

    private fun blocks(slot: String): Boolean = IconSlotPolicy.blocksSlot(slot, host, policy)

    private val nativeExpandedStacked: Boolean get() =
        surface == PlanSurface.CONTROL_CENTER_EXPANDED && network.visibility.needsNativeControlCenter

    private fun leftSlots(): Set<String> {
        val left = when (surface) {
            PlanSurface.STATUS_BAR -> policy.leftSlots
            PlanSurface.KEYGUARD ->
                if (leftMode == IconTunerContract.LEFT_MODE_HOME_AND_LOCKSCREEN) policy.leftSlots else emptySet()
            else -> emptySet()
        }
        return left.filterNot(::blocks).toSet()
    }
}

private fun Any?.stringSet(): Set<String> = (this as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
