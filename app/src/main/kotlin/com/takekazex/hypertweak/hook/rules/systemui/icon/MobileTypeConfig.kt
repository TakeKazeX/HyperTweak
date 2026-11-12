package com.takekazex.hypertweak.hook.rules.systemui.icon

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

/** A network-type label for [MobileTypeRenderer]; an empty [text] means that no bitmap is drawn. */
data class MobileTypeOutput(
    val text: String,
    val isSingle: Boolean,
    val isRoaming: Boolean,
    /**
     * The type exists, but Wi-Fi owns the network indicator while it carries data.
     */
    val suppressed: Boolean = false
)
