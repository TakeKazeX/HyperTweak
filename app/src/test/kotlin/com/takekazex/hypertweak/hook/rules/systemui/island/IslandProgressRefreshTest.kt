package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandStateKind
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import org.junit.Assert.*
import org.junit.Test

/** Placeholder removal is keyed by the content view, never by the handler that owns it now. */
class IslandProgressRefreshTest {
    private val host = InMemoryIslandHost()
    private val owner = Any()
    private val download = FakeTask("download", owner)
    private val side = FakeTask("side", owner)
    private val shell = FakeTask("uninitialised", owner)
    private val settings = object : IslandSettings {
        override var threeIslands = false
        override val landscapeFull = false
        override val landscapeMore = false
        override var removePlaceholder = true
    }
    private val releases = mutableListOf<Any>()
    private var layoutDirection = 0
    private var nativeScreenWidth = 1200
    private val ports = object : IslandHostPorts by host {
        override fun fixedCutoutWidth(view: Any) = 72
        override fun layoutDirection(view: Any): Int = layoutDirection
        override fun screenWidth(owner: Any): Int = nativeScreenWidth
        override fun call(owner: Any, member: String, vararg args: Any?): Any? = when (member) {
            "getCurrentBigIslandWidth" -> if (owner === shell) 0 else 381
            "getCurrentBigIslandX" -> 471
            "getSpace" -> 12
            "getBigIslandViewWidth" -> 381
            "getBigIslandViewWidthHasSmallIsland" -> 296
            "getBigIslandX" -> 471
            "getBigIslandXHasSmallIsland" -> 471
            else -> host.call(owner, member, *args)
        }
        override fun bigIslandView(view: Any): Any? {
            // Reached only when a module-owned position is restored to native.
            releases += view
            return null
        }
    }
    private val runtime = IslandRuntime(ports, settings, { false }, null, null)
    private val session = runtime.sessions.getOrCreate(owner)
    private val content = IslandContentSize(93, 216)

    init {
        download.state = FakeState(IslandStateKind.BIG)
        host.bind(owner, IslandSlot.BIG, download).bind(owner, IslandSlot.SMALL, side)
        host.sizes[download] = content
        host.scene = host.scene.copy(maxWidth = 722)
    }

    private fun measure() = runtime.compact.afterMeasure(download, Any(), Any(), null)

    private fun offset(left: Int = 93, right: Int = 216, width: Int = 381): Float? =
        runtime.presentation.bigIslandAnimation(download, arrayOf<Any?>(left, right, width, 0f, null, null, null, null, download))
            .let { it as? com.takekazex.hypertweak.hook.rules.systemui.island.session.TemplateAnimation.Redirect }
            ?.args?.get(3) as? Float

    @Test fun `camera stays centred while each side keeps its own width`() {
        val measured = checkNotNull(measure())
        assertEquals(471, measured.x)
        assertEquals(381, measured.width)
        assertEquals(93, measured.left)
        assertEquals(216, measured.right)
        assertEquals(measured.width, measured.viewWidth)
        assertEquals(61.5f, offset())
    }

    @Test fun `repeated progress binding keeps the published offset`() {
        measure()
        repeat(50) {
            runtime.templates.templateBound(download)
            assertFalse(session.health.failed)
            assertEquals(61.5f, offset())
            assertTrue(releases.isEmpty())
        }
    }

    @Test fun `new progress text width updates geometry`() {
        measure()
        host.sizes[download] = IslandContentSize(103, 226)
        val measured = checkNotNull(measure())
        assertEquals(461, measured.x)
        assertEquals(401, measured.width)
        assertEquals(61.5f, offset(103, 226, 401))
        assertNull(offset())
    }

    @Test fun `leaving the main slot keeps compact geometry for the exit and return animations`() {
        measure()
        // Expand and delete both clear the BIG handler before the host animates the view.
        host.bind(owner, IslandSlot.BIG, null).bind(owner, IslandSlot.EXPANDED, download)
        download.state = FakeState(IslandStateKind.OTHER)
        runtime.presentation.beforeStateChange(session, download)
        assertEquals(61.5f, offset())
        assertTrue(releases.isEmpty())
        // A native re-measure while expanded must not write the mirrored native width back.
        assertEquals(381, checkNotNull(measure()).width)
        assertEquals(61.5f, offset())
    }

    @Test fun `only the field set carrying compact geometry is redirected`() {
        measure()
        assertNull(offset(120, 120, 312))
    }

    @Test fun `disabled row geometry cannot borrow placeholder removal to keep projecting`() {
        settings.threeIslands = true
        val claimed = com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandResultGeometry(
            381, 93, 216, 471, 0, 381, 93, 216, 471)
        runtime.compact.afterMeasure(download, Any(), Any(), claimed)
        assertEquals(61.5f, offset())
        host.bind(owner, IslandSlot.BIG, shell)
        assertNull(runtime.coordinates.nativeSmallTargetX(session))
        settings.threeIslands = false
        // A disabled row cannot project its old edge through placeholder removal.
        assertNull(offset())
        assertNull(runtime.coordinates.nativeSmallTargetX(session))
        host.bind(owner, IslandSlot.BIG, download)
        measure()
        assertEquals(61.5f, offset())
    }

    @Test fun `a small island that no longer fits keeps the native result`() {
        host.measuredSmall = 400
        assertNull(measure())
        assertNull(offset())
    }

    @Test fun `disabling placeholder removal returns the next measurement to native`() {
        measure()
        settings.removePlaceholder = false
        assertNull(measure())
        assertNull(offset())
    }

    @Test fun `landscape never rewrites the main template`() {
        host.orientation = com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation.LANDSCAPE
        assertNull(measure())
    }

    @Test fun `native coordinates are never reconstructed from old template edges`() {
        measure()
        host.bind(owner, IslandSlot.BIG, shell)
        assertNull(runtime.coordinates.nativeSmallTargetX(session))
        assertNull(runtime.coordinates.nativeCoordinateX(session, download))
        settings.removePlaceholder = false
        host.orientation = com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation.LANDSCAPE
        assertNull(runtime.coordinates.nativeCoordinateX(session, download))
    }
}
