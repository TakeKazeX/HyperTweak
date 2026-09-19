package com.takekazex.hypertweak.hook.rules.systemui.icon

import java.util.Locale

internal data class SmallMobileTypeLabel(
    val baseText: String,
    val suffixText: String
)

/** Shared 5GA label parsing for the native, bitmap, Duo, and preview renderers. */
internal object MobileTypeLabelStyle {
    const val SMALL_5GA_SUFFIX_SCALE = 0.62f

    /**
     * Recognizes the host's 5GA spelling and the 5G-A variant seen in some mobile models. A
     * roaming prefix remains part of the base label; the visual result is always `5G` plus a
     * smaller `A`, matching the status-bar reference.
     */
    fun small5GaParts(raw: String): SmallMobileTypeLabel? {
        val label = raw.trim()
        if (label.isEmpty()) return null
        val upper = label.uppercase(Locale.ROOT)
        val matchedSuffix = when {
            upper.endsWith("5G-A") -> "5G-A"
            upper.endsWith("5G A") -> "5G A"
            upper.endsWith("5GA") -> "5GA"
            else -> return null
        }
        val prefix = label.dropLast(matchedSuffix.length)
        if (prefix.isNotEmpty() && !prefix.equals("R", ignoreCase = true)) return null
        return SmallMobileTypeLabel(baseText = "${prefix}5G", suffixText = "A")
    }
}
