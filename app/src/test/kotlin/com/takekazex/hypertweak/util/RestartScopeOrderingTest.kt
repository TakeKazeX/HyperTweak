package com.takekazex.hypertweak.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RestartScopeOrderingTest {
    @Test
    fun `restart count is primary and automatic selection breaks ties`() {
        val packages = listOf(
            "com.example.low",
            "com.example.busy",
            "com.example.same-manual",
            "com.example.same-auto"
        )

        assertEquals(
            listOf(
                "com.example.busy",
                "com.example.same-auto",
                "com.example.same-manual",
                "com.example.low"
            ),
            smartRestartScopeOrder(
                packages = packages,
                restartCounts = mapOf(
                    "com.example.busy" to 8,
                    "com.example.same-manual" to 3,
                    "com.example.same-auto" to 3,
                    "com.example.low" to 1
                ),
                automaticallySelectedPackages = setOf("com.example.same-auto")
            )
        )
    }

    @Test
    fun `equal rows keep their original position`() {
        val packages = listOf(
            "com.example.first",
            "com.example.second",
            "com.example.third"
        )

        assertEquals(
            packages,
            smartRestartScopeOrder(
                packages = packages,
                restartCounts = emptyMap(),
                automaticallySelectedPackages = emptySet()
            )
        )
    }

    @Test
    fun `manual selection is not part of automatic ordering`() {
        val packages = listOf("com.example.first", "com.example.second")
        val counts = mapOf("com.example.first" to 1, "com.example.second" to 1)

        val beforeManualCheck = smartRestartScopeOrder(packages, counts, emptySet())
        val afterManualCheck = smartRestartScopeOrder(packages, counts, emptySet())

        assertEquals(beforeManualCheck, afterManualCheck)
    }
}
