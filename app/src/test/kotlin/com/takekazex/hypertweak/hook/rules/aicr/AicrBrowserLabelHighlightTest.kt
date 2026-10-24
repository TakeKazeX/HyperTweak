package com.takekazex.hypertweak.hook.rules.aicr

import com.takekazex.hypertweak.hook.rules.aicr.AicrBrowserCardText.Range
import org.junit.Assert.*
import org.junit.Test

class AicrBrowserLabelHighlightTest {
    @Test
    fun nativeApplicationRangeAdaptsToAnyInstalledBrowserLabel() {
        for (label in listOf("Microsoft Edge", "Via", "Firefox", "Chrome", "浏览器", "自定义🌏浏览器名称")) {
            // The host's vendor label need not equal the localized PackageManager label.
            val result = AicrBrowserCardText.rewrite("在小米浏览器中打开", listOf(Range(1, 6)), "浏览器", label)!!
            assertEquals("在${label}中打开", result.text)
            assertEquals(listOf(Range(1, 1 + label.length)), result.highlights)
            val repeated = AicrBrowserCardText.rewrite(result.text, result.highlights, label, label)!!
            assertEquals(result, repeated)
        }
    }

    @Test
    fun switchingDefaultBrowserUsesTheNativeRangeInsteadOfKnownNames() {
        val result = AicrBrowserCardText.rewrite("Open in Unlisted Browser", listOf(Range(8, 24)), null, "Via")!!
        assertEquals("Open in Via", result.text)
        assertEquals(listOf(Range(8, 11)), result.highlights)
    }

    @Test
    fun missingHighlightIsRestoredForTheActualApplicationName() {
        val result = AicrBrowserCardText.rewrite("在Microsoft Edge中打开", emptyList(), "Microsoft Edge", "Via")!!
        assertEquals("Via", result.text)
        assertEquals(listOf(Range(0, 3)), result.highlights)
    }

    @Test
    fun alreadyRewrittenLabelGetsItsMissingColorRangeBack() {
        val result = AicrBrowserCardText.rewrite("在Via中打开", emptyList(), "原浏览器", "Via")!!
        assertEquals(listOf(Range(0, 3)), result.highlights)
    }

    @Test
    fun otherHighlightsShiftWithoutChangingTheirText() {
        val result = AicrBrowserCardText.rewrite("在原浏览器中打开", listOf(Range(1, 5), Range(6, 8)), "原浏览器", "Microsoft Edge")!!
        assertEquals("在Microsoft Edge中打开", result.text)
        assertEquals("打开", result.text.substring(result.highlights[1].start, result.highlights[1].end))
    }

    @Test
    fun utf16OffsetsPreserveSupplementaryCharacters() {
        val result = AicrBrowserCardText.rewrite("🌏在旧浏览器中打开", listOf(Range(3, 7)), "旧浏览器", "🦊 Firefox")!!
        assertEquals("🌏在🦊 Firefox中打开", result.text)
        assertEquals("🦊 Firefox", result.text.substring(result.highlights[0].start, result.highlights[0].end))
    }

    @Test
    fun missingAmbiguousOrVendorPrefixedNamesUseOnlyTheTruthfulApplicationLabel() {
        for ((text, ranges) in listOf(
            "打开网页" to emptyList(),
            "打开网页" to listOf(Range(0, 1), Range(2, 3)),
            "在小米浏览器中打开" to listOf(Range(-1, 100)),
            "在小米浏览器中打开" to emptyList()
        )) {
            val result = AicrBrowserCardText.rewrite(text, ranges, "浏览器", "Chrome")!!
            assertEquals("Chrome", result.text)
            assertEquals(listOf(Range(0, 6)), result.highlights)
        }
        assertNull(AicrBrowserCardText.rewrite("打开网页", emptyList(), "网页", ""))
    }
}
