package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.presentation.icon.MobileSignalState

/** Settings that affect only the network-type text rendered inside the icon slots. */
data class MobileTypeConfig(
    val hideWhenDisconnected: Boolean = false,
    /**
     * Wi-Fi carries the data connection. Report suppression separately from a missing label.
     */
    val hideWhenWifiAvailable: Boolean = false,
    val showRoamingPrefix: Boolean = false,
    val textSizeSp: Float = 14f,
    val weight: Int = 630,
    val singleWeight: Int = 400,
    val condensedWidthPercent: Int = 80,
    val paddingStartSp: Float = 2f,
    val paddingEndSp: Float = 2f,
    val verticalOffsetSp: Float = 0f,
    val fontMode: Int = 0,
    val typography: com.takekazex.hypertweak.settings.contract.MobileTypeTypography = com.takekazex.hypertweak.settings.contract.MobileTypeTypography(),
    val small5GaEnabled: Boolean = false
) {
    fun safe(): MobileTypeConfig = copy(
        textSizeSp = textSizeSp.takeIf(Float::isFinite)?.coerceIn(1f, 48f) ?: 14f,
        weight = weight.coerceIn(100, 900),
        singleWeight = singleWeight.coerceIn(100, 900),
        condensedWidthPercent = condensedWidthPercent.coerceIn(40, 100),
        paddingStartSp = paddingStartSp.takeIf(Float::isFinite)?.coerceIn(0f, 48f) ?: 2f,
        paddingEndSp = paddingEndSp.takeIf(Float::isFinite)?.coerceIn(0f, 48f) ?: 2f,
        verticalOffsetSp = verticalOffsetSp.takeIf(Float::isFinite)?.coerceIn(-48f, 48f) ?: 0f,
        fontMode = fontMode.coerceIn(0, 3)
    )
}

/** Result of the type policy; an empty [text] means that no type bitmap should be published. */
data class MobileTypeOutput(
    val text: String,
    val isSingle: Boolean,
    val isRoaming: Boolean,
    /**
     * The type exists, but Wi-Fi owns the network indicator while it carries data.
     */
    val suppressed: Boolean = false
)

/**
 * Pure network-type policy. The active data subscription is selected by id, while row order is
 * left untouched. In particular, a blank type never receives an `R` prefix.
 */
object MobileTypePolicy {
    fun resolve(state: MobileSignalState, config: MobileTypeConfig): MobileTypeOutput {
        val safe = config.safe()
        val active = state.subscriptions[state.activeDataSubId] ?: return empty(state)
        val raw = when {
            state.wifiConnected -> state.wifiCellularType(wifiDefault = true).orEmpty()
            active.dataConnected == false -> active.typeWhileOnWifi().orEmpty()
            else -> active.networkType?.trim().orEmpty()
        }
        if (raw.isEmpty()) return empty(state)
        if (active.dataConnected == false && safe.hideWhenDisconnected) return empty(state)
        // The same default-route input also drives the whole-mobile visibility rule.
        val suppressed = safe.hideWhenWifiAvailable && state.wifiConnected
        val roaming = active.roaming == true
        val text = if (safe.showRoamingPrefix && roaming && !raw.startsWith("R")) {
            "R$raw"
        } else {
            raw
        }
        return MobileTypeOutput(text, state.subscriptionOrder.size == 1, roaming, suppressed)
    }

    private fun empty(state: MobileSignalState) = MobileTypeOutput(
        text = "",
        isSingle = state.subscriptionOrder.size == 1,
        isRoaming = false
    )
}
