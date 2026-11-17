package com.takekazex.hypertweak.hook.rules.systemui.island.flashlight

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashlightIslandModelTest {
    @After fun reset() = FlashlightIslandHold.clear()

    @Test fun levelsMatchTheNativeStrengthConversion() {
        assertEquals(1, FlashlightLevels.strength(0f, 100))
        assertEquals(57, FlashlightLevels.strength(0.57f, 100))
        assertEquals(100, FlashlightLevels.strength(2f, 100))
        assertEquals(0.01f, FlashlightLevels.clamp(0f, 100), 1e-6f)
        assertEquals(0.5f, FlashlightLevels.logic(50, 100), 1e-6f)
        assertEquals(1, FlashlightLevels.strength(1f, 1))
    }

    @Test fun sliderFollowsTheNativePageRules() {
        // One slider length of travel is the whole range; dragging to the bottom reaches 0 (off).
        assertEquals(80, FlashlightSlider.drag(30, 50f, 100f))
        assertEquals(100, FlashlightSlider.drag(30, 500f, 100f))
        assertEquals(0, FlashlightSlider.drag(30, -40f, 100f))
        assertTrue(FlashlightSlider.isEdge(0))
        assertTrue(FlashlightSlider.isEdge(100))
        assertFalse(FlashlightSlider.isEdge(50))
        assertEquals(5, FlashlightSlider.shown(true, 1))
        assertEquals(0, FlashlightSlider.shown(false, 3))
        assertEquals(0.15f, FlashlightSlider.renderProgress(true, 3f), 1e-6f)
        assertEquals(0f, FlashlightSlider.renderProgress(false, 0f), 1e-6f)
        assertEquals(0.6f, FlashlightSlider.renderProgress(true, 60f), 1e-6f)
    }

    @Test fun onlyTheFlashlightBusinessIsRecognised() {
        assertTrue(FlashlightLevels.isFlashlightFocus("""{"param_v2":{"business":"miui_flashlight"}}"""))
        assertFalse(FlashlightLevels.isFlashlightFocus("""{"param_v2":{"business":"miui_timer"}}"""))
        assertFalse(FlashlightLevels.isFlashlightFocus("not json"))
        assertFalse(FlashlightLevels.isFlashlightFocus(null))
    }

    @Test fun closeIsDeferredOnlyWhileThePanelIsHeld() {
        assertEquals(0, FlashlightIslandHold.onNativeStatus(false, 0))
        assertFalse(FlashlightIslandHold.takeSuppressedClose())

        assertEquals(1, FlashlightIslandHold.onNativeStatus(true, 1))

        FlashlightIslandHold.held = true
        // The island already exists: a repost would only re-add the panel and rebuild its surface.
        assertEquals(3, FlashlightIslandHold.onNativeStatus(true, 1))
        assertEquals(3, FlashlightIslandHold.onNativeStatus(true, 2))
        assertEquals(2, FlashlightIslandHold.onNativeStatus(false, 2))
        assertEquals(3, FlashlightIslandHold.onNativeStatus(false, 0))
        assertTrue(FlashlightIslandHold.takeSuppressedClose())
        assertFalse(FlashlightIslandHold.takeSuppressedClose())

        // Turning the torch back on cancels the pending close.
        assertEquals(3, FlashlightIslandHold.onNativeStatus(false, 0))
        FlashlightIslandHold.onNativeStatus(true, 1)
        assertFalse(FlashlightIslandHold.takeSuppressedClose())
    }
}
