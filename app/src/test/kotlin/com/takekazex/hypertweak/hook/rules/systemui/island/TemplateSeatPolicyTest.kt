package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.TemplateSeatMode
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.TemplateSeatPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateSeatPolicyTest {
    private val full = FullIslandFrame(300, IslandContentSize(90, 90))
    private val compact = full.copy(full = false)

    @Test fun `admitted side music always releases the camera seat`() {
        assertEquals(TemplateSeatMode.CAMERA_RELEASING, TemplateSeatPolicy.modeFor(true, false, compact))
        assertEquals(TemplateSeatMode.CAMERA_RELEASING, TemplateSeatPolicy.modeFor(true, true, null))
    }

    @Test fun `landscape row releases the camera seat only for full frames`() {
        assertEquals(TemplateSeatMode.CAMERA_RELEASING, TemplateSeatPolicy.modeFor(false, true, full))
        assertEquals(TemplateSeatMode.NORMAL, TemplateSeatPolicy.modeFor(false, true, compact))
        assertEquals(TemplateSeatMode.NORMAL, TemplateSeatPolicy.modeFor(false, true, null))
    }

    @Test fun `portrait main and compact templates keep the native camera seat`() {
        assertEquals(TemplateSeatMode.NORMAL, TemplateSeatPolicy.modeFor(false, false, full))
        assertEquals(TemplateSeatMode.NORMAL, TemplateSeatPolicy.modeFor(false, false, compact))
        assertEquals(TemplateSeatMode.NORMAL, TemplateSeatPolicy.modeFor(false, false, null))
    }
}
