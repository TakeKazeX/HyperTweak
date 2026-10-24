package com.takekazex.hypertweak.hook.rules.aicr

/** AICR's cue builder uses String.indexOf/length: these are UTF-16, exclusive-end ranges. */
internal object AicrBrowserCardText {
    data class Range(val start: Int, val end: Int)
    data class Result(val text: String, val highlights: List<Range>)

    fun rewrite(text: String, highlights: List<Range>, sourceLabel: String?, browserLabel: String): Result? {
        if (browserLabel.isBlank()) return null
        val valid = highlights.filter { it.start >= 0 && it.end > it.start && it.end <= text.length }
        // Copy Direct's native title builder highlights the application name. That range is
        // authoritative even when its vendor name differs from PackageManager's localized label.
        val nameRange = valid.singleOrNull() ?: valid.filter { range ->
            val value = text.substring(range.start, range.end)
            value == sourceLabel || value == browserLabel
        }.singleOrNull() ?: if (text.isNotEmpty() && (text == sourceLabel || text == browserLabel)) {
            Range(0, text.length)
        } else {
            // Do not guess an app-name substring: a vendor's "小米浏览器" may have the PM
            // label "浏览器". A confirmed browser card can always use the real label alone.
            return Result(browserLabel, listOf(Range(0, browserLabel.length)))
        }
        val updated = text.replaceRange(nameRange.start, nameRange.end, browserLabel)
        val delta = browserLabel.length - (nameRange.end - nameRange.start)
        fun offset(value: Int, end: Boolean): Int = when {
            value <= nameRange.start -> value
            value >= nameRange.end -> value + delta
            end -> nameRange.start + browserLabel.length
            else -> nameRange.start
        }
        val remapped = valid.map { Range(offset(it.start, false), offset(it.end, true)) }
        val browserRange = Range(nameRange.start, nameRange.start + browserLabel.length)
        return Result(updated, (remapped + browserRange).distinct())
    }
}
