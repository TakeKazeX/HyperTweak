package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandGateSnapshotTest {
    private val host = InMemoryIslandHost()
    private val owner = Any()
    private val settings = object : IslandSettings {
        override var threeIslands = true
        override var landscapeFull = false
        override var landscapeMore = false
        override val removePlaceholder = true
    }
    private val runtime = IslandRuntime(host, settings, { false }, null, null)
    private val session = runtime.sessions.getOrCreate(owner)

    init {
        host.bind(owner, IslandSlot.BIG, FakeTask("timer", owner))
    }

    @Test fun readOnlyScopeKeepsHostStructureUntilItCloses() {
        runtime.readOnlyGate {
            assertTrue(runtime.enabled(session))
            host.orientation = IslandOrientation.UNDEFINED
            assertTrue(runtime.enabled(session))
        }
        assertFalse(runtime.enabled(session))
    }

    @Test fun preferencesAndHealthAreNeverSnapshotted() {
        runtime.readOnlyGate {
            assertTrue(runtime.enabled(session))
            settings.threeIslands = false
            assertFalse(runtime.enabled(session))
            settings.threeIslands = true
            session.health.fail(IslandFeature.PRESENTATION)
            assertFalse(runtime.enabled(session))
        }
    }
}
