package com.takekazex.hypertweak.hook.rules.systemui.icon

/** Pure mapping for the numeric Wi-Fi standard labels exposed by MIUI. */
object WifiStandardPolicy {
    val DEFAULT_MAP: List<Int> = listOf(4, 5, 6, 7, 8)

    /**
     * Parses the five-entry map used by the source module. One value is broadcast to all five
     * standards; exactly five values are accepted. `-1` is the explicit hidden value.
     */
    fun parseMap(raw: String?): List<Int> {
        val values = raw.orEmpty()
            .split(',', '，', ' ', '\n', '\t')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull(String::toIntOrNull)
        return when {
            values.size == 1 -> List(5) { values[0] }
            values.size == 5 -> values
            else -> DEFAULT_MAP
        }
    }

    /** Returns the displayed label, where zero means the standard row is hidden. */
    fun resolve(mode: Int, rawStandard: Int?, original: Int = 0, map: List<Int> = DEFAULT_MAP): Int {
        if (mode !in 2..3) return original
        val raw = rawStandard ?: original
        if (raw !in 4..8) return 0
        return if (mode == 2) raw else map.getOrElse(raw - 4) { raw }.coerceAtLeast(0)
    }
}
