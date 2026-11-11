package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandScene
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SafeInsets
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import org.junit.Assert.*
import org.junit.Test

/** Device sample: a lyric template fills AT_MOST(screen / 2) on both sides in landscape. */
class IslandLandscapeRowTest {
    private val host = InMemoryIslandHost()
    private val owner = Any()
    private val timer = FakeTask("timer", owner)
    private val media = FakeTask("media", owner)
    private val settings = object : IslandSettings {
        override val threeIslands = false
        override val landscapeFull = true
        override val landscapeMore = true
        override val removePlaceholder = true
    }
    private val runtime = IslandRuntime(host, settings, { false }, null, null)
    private val session = runtime.sessions.getOrCreate(owner)

    init {
        host.orientation = IslandOrientation.LANDSCAPE
        host.scene = IslandScene(2608, 1200, 243, 243, 12, 59, 714, SafeInsets.NONE, IslandOrientation.LANDSCAPE)
        host.bind(owner, IslandSlot.BIG, timer).bind(owner, IslandSlot.SMALL, media)
        host.sizes[timer] = IslandContentSize(93, 184)
        host.sizes[media] = IslandContentSize(1304, 1304)
        session.scene.layoutIntent = true
    }

    @Test fun `a filling lyric template is capped at the native maximum and the full row still fits`() {
        runtime.measurement.afterMeasure(media, Any(), Any(), Any())
        assertEquals(IslandContentSize(357, 357), session.scene.contentSizes[media])
        val main = checkNotNull(runtime.measurement.afterMeasure(timer, Any(), Any(), Any()))
        val layout = checkNotNull(session.scene.layout)
        assertTrue(layout.full)
        assertEquals(714, session.scene.fullFrames.getValue(media).width)
        assertEquals(277, main.width)
        assertEquals(93, main.left)
        assertEquals(184, main.right)
    }

    @Test fun `the narrow side keeps its natural width when the cap applies`() {
        assertEquals(IslandContentSize(93, 621), IslandContentSize(93, 1304).fitted(714))
        assertEquals(IslandContentSize(621, 93), IslandContentSize(1304, 93).fitted(714))
        assertEquals(IslandContentSize(93, 184), IslandContentSize(93, 184).fitted(714))
    }
}
