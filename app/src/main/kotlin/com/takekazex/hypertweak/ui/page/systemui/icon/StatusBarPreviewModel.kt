package com.takekazex.hypertweak.ui.page.systemui.icon

import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconSlotPolicyConfig
import com.takekazex.hypertweak.presentation.icon.MobileSignalEvent
import com.takekazex.hypertweak.presentation.icon.MobileSignalModel
import com.takekazex.hypertweak.presentation.icon.MobileSignalState
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility
import com.takekazex.hypertweak.presentation.icon.duo.DuoBattery
import com.takekazex.hypertweak.presentation.icon.duo.DuoContent
import com.takekazex.hypertweak.presentation.icon.duo.DuoLayout
import com.takekazex.hypertweak.presentation.icon.duo.DuoPolicy
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow
import com.takekazex.hypertweak.settings.contract.IconSurface

/**
 * Pseudo-slot the preview draws with the production Duo glyph. It is never a host slot name.
 */
internal const val PREVIEW_DUO_SLOT = "duo"

/** Native per-subscription mobile slots: the ROM binds one of these per active SIM. */
internal const val SLOT_SINGLE_SIM1 = "single_mobile_sim1"
internal const val SLOT_SINGLE_SIM2 = "single_mobile_sim2"

/** The module's replacement slot for the stacked dual-row signal. */
private const val SLOT_STACKED = IconSlotPolicy.SLOT_STACKED_SIGNAL

/** Host slot every native per-subscription mobile holder is published under. */
private const val SLOT_WIFI = "wifi"
private const val SLOT_BATTERY = "handle_battery"

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
    val duoContent: DuoContent? = null,
    val small5GaEnabled: Boolean = false,
    val typography: com.takekazex.hypertweak.settings.contract.MobileTypeTypography = com.takekazex.hypertweak.settings.contract.MobileTypeTypography()
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
    val keepCellularTypeOnWifi: Boolean = false,
    val hideNonDefaultSim: Boolean = false,
    val hideSimOne: Boolean = false,
    val hideSimTwo: Boolean = false,
    val small5GaEnabled: Boolean = false,
    val rotationLockEnabled: Boolean = false,
    val typography: com.takekazex.hypertweak.settings.contract.MobileTypeTypography = com.takekazex.hypertweak.settings.contract.MobileTypeTypography()
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
        enabledModuleSlots = if (input.stackedEnabled) IconSlotPolicy.STACKED_SLOTS.toSet() else emptySet(),
        leftSlots = input.leftSlots
    )
    val order = IconSlotPolicy.normalizeOrder(IconSlotCatalog.slots, config)
    val hostIndex = { slot: String ->
        order.indexOf(slot).let { if (it < 0) Int.MAX_VALUE else it }
    }

    val rotation = IconSlotPolicy.SLOT_ROTATION_LOCK
    val showRotation = input.rotationLockEnabled && IconSlotPolicy.rotationLockShowsOnStatusBar(
        IconSlotPolicy.modeFor(rotation, modes), rotation in input.leftSlots
    )
    val left = leftPreviewSlots(input.leftSlots)
        .filter { it != rotation || showRotation }
        .sortedBy(hostIndex)
        .take(PREVIEW_MAX_LEFT_SLOTS)
    val indicatorSample = if (showRotation) PREVIEW_INDICATOR_SLOTS + rotation else PREVIEW_INDICATOR_SLOTS
    val indicators = indicatorSample
        .filterNot { it in input.leftSlots }
        .filterNot { previewHidden(it, config) }
        .sortedBy(hostIndex)
        .take(PREVIEW_MAX_INDICATOR_SLOTS)
    val visibility = NetworkIconVisibility(
        hideMobileOnWifi = input.hideMobileOnWifi,
        hideWifi = input.hideWifiConnected,
        hideType = !input.showCellularType,
        hideNonDefaultSim = input.hideNonDefaultSim,
        hideSimOne = input.hideSimOne,
        hideSimTwo = input.hideSimTwo,
        slotModes = modes
    )
    val hidden = visibility.hiddenCellularIds(previewMobileState, IconSurface.STATUS_BAR, wifiDefault = true) { it - 1 }
    val hideWifi = visibility.hidesWifi(IconSurface.STATUS_BAR, connected = true)
    val signal = signalPreviewSlots(input, config, hidden)
    val core = buildList {
        addAll(signal)
        // Duo folds the battery arc, the network state and the data card into one glyph, so its
        // glyph replaces the whole pinned cluster instead of joining it.
        if (!input.duoEnabled) {
            if (!hideWifi) add(SLOT_WIFI)
            // The order policy may front Wi-Fi; the battery never moves, because the host draws it
            // in a separate trailing container rather than in the icon row.
            sortBy { slot ->
                val hostSlot = when (slot) {
                    SLOT_SINGLE_SIM1, SLOT_SINGLE_SIM2 -> if (input.stackedEnabled) SLOT_STACKED else IconSlotPolicy.SLOT_MOBILE
                    else -> slot
                }
                hostIndex(hostSlot)
            }
            if (!previewHidden(SLOT_BATTERY, config)) add(SLOT_BATTERY)
        }
    }

    return StatusBarPreviewModel(
        leftSlots = left,
        indicatorSlots = indicators,
        coreSlots = core,
        duoSizeDp = DuoLayout.safeSizeDp(input.duoSizeDp),
        showNetworkType = input.showCellularType && input.keepCellularTypeOnWifi && 1 !in hidden && signal.isNotEmpty() && !input.duoEnabled &&
            (!input.stackedEnabled || !previewHidden(IconSlotPolicy.SLOT_STACKED_TYPE, config)),
        duoContent = if (input.duoEnabled) {
            val content = if (input.keepCellularTypeOnWifi) {
                DuoPolicy.withRetainedWifiType(previewDuoContent, previewMobileState)
            } else previewDuoContent
            DuoPolicy.applyVisibility(content, hidden, hideType = visibility.hideType, hideWifi = hideWifi)
                .copy(typography = input.typography)
        } else null,
        small5GaEnabled = input.small5GaEnabled,
        typography = input.typography
    )
}

/**
 * The signal group for the selected display mode.
 *
 * Native is the ROM's own layout: one mobile holder per subscription, so a dual-SIM bar shows both
 * cards' badged signal icons. Stacked replaces the pair with the module's two-row glyph, and Duo
 * folds signal, WiFi and battery into one glyph.
 */
private val previewMobileState = MobileSignalState()
    .reduce(MobileSignalEvent.Subscriptions(listOf(1, 2)))
    .reduce(MobileSignalEvent.ActiveDataSubId(1))
    .reduce(MobileSignalEvent.SignalModel(1, MobileSignalModel.cellular(4, 5)))
    .reduce(MobileSignalEvent.InService(1, true))
    .reduce(MobileSignalEvent.NetworkType(1, "5G"))

private val previewDuoContent = DuoContent(
    DuoBattery(80, false, false), 4, null, 4, false, false,
    cellularSignalLevels = listOf(4, 2),
    cellularSignalRows = listOf(DuoSignalRow(1, 0, 4), DuoSignalRow(2, 1, 2)),
    activeDataSubId = 1
)

private fun signalPreviewSlots(
    input: StatusBarPreviewInput,
    config: IconSlotPolicyConfig,
    hidden: Set<Int>
): List<String> {
    if (input.duoEnabled) return listOf(PREVIEW_DUO_SLOT)
    val visible = buildList {
        if (1 !in hidden) add(SLOT_SINGLE_SIM1)
        if (2 !in hidden) add(SLOT_SINGLE_SIM2)
    }
    if (!input.stackedEnabled) return visible
    if (previewHidden(SLOT_STACKED, config)) return emptyList()
    return if (visible.size > 1) listOf(SLOT_STACKED) else visible
}

private fun previewHidden(slot: String, config: IconSlotPolicyConfig): Boolean =
    IconSlotPolicy.blocksSlot(slot, IconSurface.STATUS_BAR, config)

/**
 * The user's left-placed host slots, reduced to glyphs the catalog can draw.
 *
 * A left-placed slot stays out of the right
 * cluster even when it exceeds [PREVIEW_MAX_LEFT_SLOTS], because the hook hides it there too.
 */
private fun leftPreviewSlots(leftSlots: Set<String>): List<String> =
    leftSlots.filter { IconSlotCatalog.of(it) != null }.distinct()
