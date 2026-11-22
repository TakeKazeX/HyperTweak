package com.takekazex.hypertweak.hook.rules.phrase

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardTextCoalescerTest {
    private data class Row(val time: Long, val payload: String?)

    private val plain = """[{"text":"same text","dataSize":0}]"""
    private val formatted = """[{"text":"same text","dataSize":0,"htmlText":"<b>same text</b>"}]"""

    @Test
    fun keepsHtmlForEitherArrivalOrder() {
        assertEquals(listOf(Row(9_000, formatted)), merge(Row(10_063, plain), Row(9_000, formatted)))
        assertEquals(listOf(Row(10_063, formatted)), merge(Row(10_063, formatted), Row(9_000, plain)))
    }

    @Test
    fun mergesExistingPairsWithoutMovingTheirPositions() {
        val unrelated = Row(9_500, """[{"text":"separate","dataSize":0}]""")
        val newestRich = Row(9_000, formatted)
        val oldestRich = Row(4_000, formatted)
        assertEquals(listOf(newestRich, unrelated, oldestRich), merge(
            Row(10_063, plain), unrelated, newestRich, Row(4_904, plain), oldestRich,
        ))
    }

    @Test
    fun leavesSeparateCopiesAndNonTextPayloadsAlone() {
        val cases = listOf(
            listOf(Row(10_000, plain), Row(7_999, formatted)),
            listOf(Row(10_000, plain), Row(9_000, plain)),
            listOf(Row(10_000, plain), Row(9_000, formatted.replace("same text", "different text"))),
            listOf(Row(10_000, plain), Row(9_000, formatted.replace("\"dataSize\":0", "\"dataSize\":12"))),
            listOf(Row(10_000, plain), Row(9_000, formatted.replace("\"dataSize\":0", "\"fileUri\":\"content://example/a\""))),
            listOf(Row(10_000, plain), Row(9_000, "invalid JSON")),
            listOf(Row(10_000, plain), Row(9_000, null)),
        )
        cases.forEach { rows ->
            assertEquals(rows, merge(*rows.toTypedArray()))
        }
    }

    private fun merge(vararg rows: Row) =
        ClipboardTextCoalescer.merge(rows.toList(), Row::time, Row::payload)
}
