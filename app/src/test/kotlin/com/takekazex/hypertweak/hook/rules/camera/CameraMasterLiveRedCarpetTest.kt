package com.takekazex.hypertweak.hook.rules.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMasterLiveRedCarpetTest {

    @Test
    fun `red carpet type inserted right after ultra pixel`() {
        val order = CameraMasterLiveRedCarpet.orderedKeys(listOf("0", "2", "3"))
        assertEquals(listOf("0", "1", "2", "3"), order)
    }

    @Test
    fun `already canonical order is a no-op`() {
        assertNull(CameraMasterLiveRedCarpet.orderedKeys(listOf("0", "1", "2", "3")))
    }

    @Test
    fun `misplaced red carpet entry is re-positioned`() {
        val order = CameraMasterLiveRedCarpet.orderedKeys(listOf("0", "2", "1", "3"))
        assertEquals(listOf("0", "1", "2", "3"), order)
    }

    @Test
    fun `table without ultra pixel inserts at front`() {
        val order = CameraMasterLiveRedCarpet.orderedKeys(listOf("2", "3"))
        assertEquals(listOf("1", "2", "3"), order)
    }

    @Test
    fun `unknown keys keep relative order after the known ones`() {
        val order = CameraMasterLiveRedCarpet.orderedKeys(listOf("0", "5", "3", "x"))
        assertEquals(listOf("0", "1", "5", "3", "x"), order)
    }

    @Test
    fun `movement segments are consistent when sizes follow the invariant`() {
        // K100 type-3 shape: 3 roles × 2 zoom floats + 3 range strings
        assertTrue(CameraMasterLiveRedCarpet.segmentsConsistent(roles = 3, zoomPairs = 6, rangeStrings = 3))
        // range strings may be absent (consumers fall back to the {1,1} range)
        assertTrue(CameraMasterLiveRedCarpet.segmentsConsistent(roles = 3, zoomPairs = 6, rangeStrings = null))
        // ultra-pixel style entry has no movement segments at all
        assertTrue(CameraMasterLiveRedCarpet.segmentsConsistent(roles = null, zoomPairs = null, rangeStrings = null))
        assertTrue(CameraMasterLiveRedCarpet.segmentsConsistent(roles = 0, zoomPairs = 0, rangeStrings = 0))
    }

    @Test
    fun `inconsistent segments are rejected before they can IOOBE mid capture`() {
        assertFalse(CameraMasterLiveRedCarpet.segmentsConsistent(roles = 3, zoomPairs = 4, rangeStrings = 3))
        assertFalse(CameraMasterLiveRedCarpet.segmentsConsistent(roles = 3, zoomPairs = 6, rangeStrings = 2))
        assertFalse(CameraMasterLiveRedCarpet.segmentsConsistent(roles = 2, zoomPairs = 4, rangeStrings = 3))
        assertFalse(CameraMasterLiveRedCarpet.segmentsConsistent(roles = 3, zoomPairs = null, rangeStrings = 3))
    }
}
