package com.takekazex.hypertweak.ui.page

import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotMode
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicy
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicyConfig
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoLayout

/**
 * Pseudo-slot the preview draws with the production Duo glyph. It is never a host slot name.
 */
internal const val PREVIEW_DUO_SLOT = "duo"

/** Native per-subscription mobile slots: the ROM binds one of these per active SIM. */
private const val SLOT_SINGLE_SIM1 = "single_mobile_sim1"
private const val SLOT_SINGLE_SIM2 = "single_mobile_sim2"

/** The module's replacement slot for the stacked dual-row signal. */
private const val SLOT_STACKED = "stacked_mobile_icon"

/** Host slot every native per-subscription mobile holder is published under. */
private const val SLOT_MOBILE = "mobile"
private const val SLOT_WIFI = "wifi"
private const val SLOT_BATTERY = "handle_battery"
private const val SLOT_COMPOUND = "compound_icon"

/**
 * Indicator sample drawn in the right cluster, before the pinned connectivity cluster.
 *
 * The host never reports which status-bar icons are actually showing to the settings process, so
 * the preview cannot be exhaustive. This is a deliberately short list: a mock that drew every slot
 * the module can reorder read as a wall of glyphs instead of a status bar. Reordering and hiding
 * still have visible feedback for these slots, and the layout/left-placement tabs move whatever
 * the user moved.
 */
internal val PREVIEW_INDICATOR_SLOTS: List<String> =
    listOf("alarm_clock", "bluetooth", "location", "zen")

/**
 * Most indicators the preview draws, so the sample above stays a sample even when more entries are
 * added later.
 */
internal const val PREVIEW_MAX_INDICATOR_SLOTS = 3

/**
 * Most left-placed icons the preview draws.
 *
 * The left container is the one region a user can fill with a dozen glyphs, and a preview that
 * mirrors all of them stops reading as a status bar. Only the first entries of the configured
 * order are drawn; a left-placed slot beyond this cap is deliberately absent rather than moved
 * back into the right cluster (the hook hides it there).
 */
internal const val PREVIEW_MAX_LEFT_SLOTS = 2

/**
 * Immutable rendering plan for one composition of the preview status bar.
 *
 * [leftSlots] draw immediately after the clock (the left container is inserted right after the
 * clock in SystemUI). [indicatorSlots] are the remaining right-cluster icons, right-aligned against
 * [coreSlots]; [coreSlots] are pinned to the end of the bar, with the battery last because the ROM
 * draws it in its own trailing container (`MiuiStatusBatteryContainer`).
 */
internal data class StatusBarPreviewModel(
    val leftSlots: List<String>,
    val indicatorSlots: List<String>,
    val coreSlots: List<String>,
    /** Duo glyph height in dp, already clamped to the same range the hook uses. */
    val duoSizeDp: Float,
    /**
     * Whether the network type label (e.g. `5G`) draws left of the signal cluster. The ROM draws
     * the type inside the mobile view, left of the bars; Duo already carries the type in its glyph.
     */
    val showNetworkType: Boolean,
    val small5GaEnabled: Boolean = false
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
    val hideWifiConnected: Boolean,
    val showCellularType: Boolean,
    val small5GaEnabled: Boolean = false
)

/**
 * Applies the icon-tuner policy to the preview sample. Pure so the layout rules are unit-tested
 * instead of only being visible on a device.
 *
 * The sample represents a WiFi-connected dual-SIM phone, which is why [StatusBarPreviewInput.
 * hideMobileOnWifi] removes the signal group and [StatusBarPreviewInput.hideWifiConnected] removes
 * the WiFi slot.
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
    val hostIndex = { slot: String ->
        order.indexOf(slot).let { if (it < 0) Int.MAX_VALUE else it }
    }

    val left = leftPreviewSlots(input.leftSlots).sortedBy(hostIndex).take(PREVIEW_MAX_LEFT_SLOTS)
    val indicators = PREVIEW_INDICATOR_SLOTS
        .filterNot { it in input.leftSlots }
        .filterNot { previewHidden(it, modes) }
        .sortedBy(hostIndex)
        .take(PREVIEW_MAX_INDICATOR_SLOTS)
    val signal = signalPreviewSlots(input, modes)
    val core = buildList {
        addAll(signal)
        // Duo folds the battery arc, the network state and the data card into one glyph, so its
        // glyph replaces the whole pinned cluster instead of joining it.
        if (!input.duoEnabled) {
            if (!input.hideWifiConnected && !previewHidden(SLOT_WIFI, modes)) add(SLOT_WIFI)
            // The order policy may front Wi-Fi; the battery never moves, because the host draws it
            // in a separate trailing container rather than in the icon row.
            sortBy(hostIndex)
            if (!previewHidden(SLOT_BATTERY, modes)) add(SLOT_BATTERY)
        }
    }

    return StatusBarPreviewModel(
        leftSlots = left,
        indicatorSlots = indicators,
        coreSlots = core,
        duoSizeDp = DuoLayout.safeSizeDp(input.duoSizeDp),
        showNetworkType = input.showCellularType && signal.isNotEmpty() && !input.duoEnabled,
        small5GaEnabled = input.small5GaEnabled
    )
}

/**
 * The signal group for the selected display mode.
 *
 * Native is the ROM's own layout: one mobile holder per subscription, so a dual-SIM bar shows both
 * cards' badged signal icons. Stacked replaces the pair with the module's two-row glyph, and Duo
 * folds signal, WiFi and battery into one glyph.
 */
private fun signalPreviewSlots(input: StatusBarPreviewInput, modes: Map<String, Int>): List<String> {
    if (input.hideMobileOnWifi) return emptyList()
    if (input.duoEnabled) return listOf(PREVIEW_DUO_SLOT)
    if (input.stackedEnabled) {
        return if (previewHidden(SLOT_STACKED, modes)) emptyList() else listOf(SLOT_STACKED)
    }
    // Every per-subscription holder is published under the host's `mobile` slot, so that slot's
    // mode - not the per-SIM customization names - is what hides or shows the native pair.
    return if (previewHidden(SLOT_MOBILE, modes)) emptyList()
    else listOf(SLOT_SINGLE_SIM1, SLOT_SINGLE_SIM2)
}

/** A module mode hides a slot from the status bar when it is "hidden" or control-center only. */
private fun previewHidden(slot: String, modes: Map<String, Int>): Boolean =
    when (IconSlotPolicy.modeFor(slot, modes)) {
        IconSlotMode.HIDE_EVERYWHERE, IconSlotMode.CONTROL_CENTER_ONLY -> true
        else -> false
    }

/**
 * The user's left-placed host slots, reduced to glyphs the catalog can draw.
 *
 * The 合成图标 row drives synthetic `compound_*` slots that carry no artwork of their own, so that
 * group collapses to the catalog's `compound_icon` row. A left-placed slot stays out of the right
 * cluster even when it exceeds [PREVIEW_MAX_LEFT_SLOTS], because the hook hides it there too.
 */
private fun leftPreviewSlots(leftSlots: Set<String>): List<String> = leftSlots.mapNotNull { slot ->
    when {
        slot.startsWith("compound_") -> SLOT_COMPOUND
        IconSlotCatalog.of(slot) != null -> slot
        else -> null
    }
}.distinct()
