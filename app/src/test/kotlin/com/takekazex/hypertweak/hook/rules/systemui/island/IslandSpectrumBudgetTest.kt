package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.*
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.*
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.spectrum.SpectrumAdmissionPolicy
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import org.junit.Assert.*
import org.junit.Test

class IslandSpectrumBudgetTest {
    private val settings = object : IslandSettings {
        override val threeIslands = true
        override val landscapeFull = false
        override val landscapeMore = false
    }

    @Test fun `policy budgets bypass paired publication while nested rendering keeps it`() {
        val host = InMemoryIslandHost()
        val owner = Any()
        val music = FakeTask("music", owner).apply { width = 102 }
        lateinit var runtime: IslandRuntime
        val hookedHost = object : IslandHostPorts by host {
            override fun nativeSmallWidth(view: Any): Int =
                runtime.coordinates.publishedSmallWidth(runtime.sessions.getOrCreate(owner), view)
                    ?: host.nativeSmallWidth(view)
        }
        runtime = IslandRuntime(hookedHost, settings, { false }, null, null)
        val session = runtime.sessions.getOrCreate(owner)
        session.scene.musicSmall = music
        session.scene.fullFrames[music] = FullIslandFrame(781, IslandContentSize(90, 90), true)
        runtime.context.withMeasuring(Any()) {
            runtime.context.withMeasuring(music) {
                assertEquals(180, hookedHost.nativeSmallWidth(music))
                assertEquals(102, runtime.budgetSmallWidth(music))
                runtime.nativeSmallWidthRead.run {
                    assertEquals(102, runtime.budgetSmallWidth(music))
                    assertEquals(102, hookedHost.nativeSmallWidth(music))
                }
                assertEquals(180, hookedHost.nativeSmallWidth(music))
                try {
                    runtime.nativeSmallWidthRead.run { error("budget failed") }
                } catch (_: IllegalStateException) { }
                assertEquals(180, hookedHost.nativeSmallWidth(music))
            }
            assertEquals(180, hookedHost.nativeSmallWidth(music))
        }
        assertFalse(runtime.nativeSmallWidthRead.active)
    }

    @Test fun `short long short content retains natural timer width before admitting waveform`() {
        val owner = Any()
        val big = FakeTask("middle", owner)
        val music = FakeTask("music", owner).apply { width = 102 }
        val other = FakeTask("other", owner).apply { width = 102 }
        val host = InMemoryIslandHost().bind(owner, IslandSlot.BIG, big)
            .bind(owner, IslandSlot.SMALL, music)
        lateinit var runtime: IslandRuntime
        val hookedHost = object : IslandHostPorts by host {
            override fun nativeSmallWidth(view: Any): Int =
                runtime.coordinates.publishedSmallWidth(runtime.sessions.getOrCreate(owner), view)
                    ?: host.nativeSmallWidth(view)
        }
        runtime = IslandRuntime(hookedHost, settings, { false }, null, null)
        val session = runtime.sessions.getOrCreate(owner)
        for (right in listOf(93, 230, 184, 93)) {
            val content = IslandContentSize(93, right)
            val outcome = IslandLayoutPolicy.measure(MeasureContext(
                scene = host.scene.copy(batteryWidth = 200), big = big, primary = music,
                stagedExtras = listOf(other), contentSizes = mapOf(big to content),
                nativeSmallWidth = runtime::budgetSmallWidth, keyOf = host::key,
                seatLeft = { it === other }, displayOrder = emptyList(),
                flags = LayoutFlags(false, false, true, false, true), minBigWidth = 200,
                nativeResult = NativeMeasureResult(338, 338, 431, 0),
                nativeMusicRight = true,
            ))
            assertEquals(content, outcome.portraitContent)
            val decision = SpectrumAdmissionPolicy.evaluate(SpectrumAdmissionInput(
                enabled = true, layout = checkNotNull(outcome.layout), extras = listOf(other),
                primary = music, expanded = null, big = big, fullFramesEmpty = true,
                mediaRendererAvailable = true, matches = { it === music },
                mediaAreasAvailable = { true }, demandOf = { MusicDemandDimensions(24, 60, 3, 0) },
                keyOf = host::key, seatLeft = { false }, nativeSmallWidth = runtime::budgetSmallWidth,
                gap = 12, clock = 170, battery = 200, safeLeft = 0, safeRight = 0,
            ))
            if (right == 93) {
                assertTrue(decision is SpectrumDecision.Admitted)
                val admitted = decision as SpectrumDecision.Admitted
                session.scene.musicSmall = music
                session.scene.fullFrames[music] = admitted.seat.frame
            } else {
                assertTrue(decision is SpectrumDecision.CoverOnly)
                session.scene.musicSmall = null
                session.scene.fullFrames.clear()
            }
        }
    }

    @Test fun `measurement retires old paired geometry before publishing a cover only frame`() {
        val host = InMemoryIslandHost()
        val owner = Any()
        val big = FakeTask("middle", owner)
        val music = FakeTask("music", owner)
        host.bind(owner, IslandSlot.BIG, big).bind(owner, IslandSlot.SMALL, music)
        host.sizes[big] = IslandContentSize(93, 184)
        host.scene = host.scene.copy(batteryWidth = 200)
        lateinit var runtime: IslandRuntime
        val hookedHost = object : IslandHostPorts by host {
            override fun nativeSmallWidth(view: Any): Int =
                runtime.coordinates.publishedSmallWidth(runtime.sessions.getOrCreate(owner), view)
                    ?: host.nativeSmallWidth(view)
        }
        runtime = IslandRuntime(hookedHost, settings, { false }, null, null)
        val session = runtime.sessions.getOrCreate(owner)
        session.scene.layoutIntent = true
        session.scene.musicSmall = music
        val layout = ThreeIslandLayout(431, 338, 317, 781, false, 1200)
        session.scene.fullFrames[music] = FullIslandFrame(781, IslandContentSize(90, 90), true)
        session.spectrum.returnGeometry =
            com.takekazex.hypertweak.hook.rules.systemui.island.policy.spectrum.SmallIslandSpectrum.ReturnGeometry(
                "music", layout, IslandContentSize(90, 90), 12, 170, 170, 0, 0, 100,
            )
        val result = runtime.measurement.afterMeasure(big, Any(), Any(), Any())
        assertNotNull(result)
        assertEquals(184, result!!.right)
        assertNull(session.scene.musicSmall)
        assertFalse(session.scene.fullFrames.containsKey(music))
        assertNull(session.spectrum.returnGeometry)
        assertNull(runtime.coordinates.publishedSmallWidth(session, music))
    }
}
