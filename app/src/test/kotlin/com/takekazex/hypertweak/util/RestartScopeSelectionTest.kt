package com.takekazex.hypertweak.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartScopeSelectionTest {
    @Test
    fun `package conversion keeps unknown scope targets`() {
        val selection = RestartScopeSelection.fromPackageSet(
            setOf(
                RestartScopeSelection.PACKAGE_SYSTEM_UI,
                RestartScopeSelection.PACKAGE_GMS,
                "com.example.keyboard"
            )
        )

        assertTrue(selection.systemUi)
        assertTrue(selection.gms)
        assertEquals(setOf("com.example.keyboard"), selection.additionalPackages)
        assertEquals(
            setOf(
                RestartScopeSelection.PACKAGE_SYSTEM_UI,
                RestartScopeSelection.PACKAGE_GMS,
                "com.example.keyboard"
            ),
            selection.toPackageSet()
        )
    }

    @Test
    fun `set operations include dynamic packages`() {
        val left = RestartScopeSelection(
            systemUi = true,
            additionalPackages = setOf("com.example.one", "com.example.shared")
        )
        val right = RestartScopeSelection(
            gms = true,
            additionalPackages = setOf("com.example.shared", "com.example.two")
        )

        assertTrue(left.merge(right).covers(left))
        assertEquals(
            setOf("com.example.shared"),
            left.intersect(right).additionalPackages
        )
        assertEquals(
            setOf("com.example.one"),
            left.without(right).additionalPackages
        )
    }
}
