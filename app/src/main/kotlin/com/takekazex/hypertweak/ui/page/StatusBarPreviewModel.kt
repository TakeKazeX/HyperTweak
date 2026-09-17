package com.takekazex.hypertweak.ui.page

import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotMode
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicy
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicyConfig
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoLayout

/**
 * Pseudo-slot the preview draws with the production Duo glyph. It is never a host slot name.
 */
internal const val PREVIEW_DUO_SLOT = "duo"

/**
 * Slots the preview treats as live.
 *
 * The host never reports which status-bar icons are actually showing to the settings process, so a
 * preview cannot be exhaustive. This is a representative "typical phone" set that covers the core
 * connectivity cluster plus the indicator slots users reorder or hide most often. Order, per-slot
 * visibility and left placement are then applied to it through [IconSlotPolicy], exactly as
 * SystemUI applies them to the real slot list.
 */
internal val PREVIEW_ACTIVE_SLOTS: List<String> = listOf(
    "network_speed",
    "alarm_clock",
    "zen",
    "bluetooth",
    "location",
    "nfc",
    "vpn",
    "hotspot",
    "airplane",
    "headset",
    "volume",
    "compound_icon",
    "wifi",
    "mobile",
    "handle_battery"
)

private val PREVIEW_CORE_SLOTS = setOf("wifi", "mobile", "handle_battery")

/**
 * Connectivity cluster pinned to the end of the bar. It stays outside the scrollable indicator
 * region, so a narrow screen can never push the battery or the signal off the visible edge.
 */
private val PINNED_CORE_SLOTS =
    PREVIEW_CORE_SLOTS + setOf("stacked_mobile_icon", PREVIEW_DUO_SLOT)

/**
 * Immutable rendering plan for one composition of the preview status bar.
 *
 * [leftSlots] draw immediately after the clock (the left container is inserted right after the
 * clock in SystemUI). [indicatorSlots] are the remaining right-cluster icons, right-aligned against
 * [coreSlots]; [coreSlots] are pinned to the end of the bar.
 */
internal data class StatusBarPreviewModel(
    val leftSlots: List<String>,
    val indicatorSlots: List<String>,
    val coreSlots: List<String>,
    /** Duo glyph height in dp, already clamped to the same range the hook uses. */
    val duoSizeDp: Float
)

/** Raw settings the preview reasons about; all values come from the page's live preference state. */
internal data class StatusBarPreviewInput(
    val position: Int,
    val customOrder: Set<String>,
    val reorderHidden: Boolean,
    val slotModes: Map<String, Int>,
    val extraHidden: Set<String>,
    val leftSlots: Set<String>,
    val stackedEnabled: Boolean,
    val duoEnabled: Boolean,
    val duoSizeDp: Float,
    val hideMobileOnWifi: Boolean,
    val hideWifiConnected: Boolean
)

/**
 * Applies the icon-tuner policy to the preview sample. Pure so the layout rules are unit-tested
 * instead of only being visible on a device.
 *
 * The sample represents a WiFi-connected phone, which is why [StatusBarPreviewInput.hideMobileOnWifi]
 * removes the signal slot and [StatusBarPreviewInput.hideWifiConnected] removes the WiFi slot.
 */
internal fun buildStatusBarPreview(input: StatusBarPreviewInput): StatusBarPreviewModel {
    val modes = IconSlotPolicy.foldExtraHiddenIntoModes(input.slotModes, input.extraHidden)
    val config = IconSlotPolicyConfig(
        position = input.position,
        customOrderEntries = input.customOrder,
        reorderHidden = input.reorderHidden,
        slotModes = modes,
        enabledModuleSlots = if (input.stackedEnabled) IconSlotPolicy.SIGNAL_SLOTS.toSet() else emptySet(),
        leftSlots = input.leftSlots
    )
    val order = IconSlotPolicy.normalizeOrder(IconSlotCatalog.slots, config)

    val slots = LinkedHashSet<String>()
    PREVIEW_ACTIVE_SLOTS.forEach { slot ->
        // Duo folds the whole core cluster into one glyph, so the three slots are dropped and the
        // pseudo-slot is added once below.
        if (slot in PREVIEW_CORE_SLOTS && input.duoEnabled) return@forEach
        if (slot == "mobile") {
            val signal = when {
                input.duoEnabled -> null
                input.stackedEnabled -> "stacked_mobile_icon"
                else -> "mobile"
            }
            if (signal != null && !previewHidden(signal, modes) && !input.hideMobileOnWifi) slots += signal
            return@forEach
        }
        if (previewHidden(slot, modes)) return@forEach
        if (slot == "wifi" && input.hideWifiConnected) return@forEach
        slots += slot
    }
    if (input.duoEnabled) slots += PREVIEW_DUO_SLOT

    val ordered = slots.sortedBy { slot ->
        val host = if (slot == PREVIEW_DUO_SLOT) "handle_battery" else slot
        order.indexOf(host).let { if (it < 0) Int.MAX_VALUE else it }
    }
    val left = ordered.filter { isLeftPreviewSlot(it, input.leftSlots) }
    val right = ordered.filterNot { isLeftPreviewSlot(it, input.leftSlots) }
    val core = right.filter { it in PINNED_CORE_SLOTS }
    val indicators = right.filterNot { it in PINNED_CORE_SLOTS }
    return StatusBarPreviewModel(left, indicators, core, DuoLayout.safeSizeDp(input.duoSizeDp))
}

/** A module mode hides a slot from the status bar when it is "hidden" or control-center only. */
private fun previewHidden(slot: String, modes: Map<String, Int>): Boolean =
    when (IconSlotPolicy.modeFor(slot, modes)) {
        IconSlotMode.HIDE_EVERYWHERE, IconSlotMode.CONTROL_CENTER_ONLY -> true
        else -> false
    }

/**
 * The compound row drives the synthetic `compound_*` slots, so its toggle moves the catalog's
 * `compound_icon` entry rather than naming it directly.
 */
private fun isLeftPreviewSlot(slot: String, leftSlots: Set<String>): Boolean = when (slot) {
    PREVIEW_DUO_SLOT -> false
    "compound_icon" -> leftSlots.any { it.startsWith("compound_") }
    else -> slot in leftSlots
}
