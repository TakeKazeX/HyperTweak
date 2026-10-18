package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMusicSpectrum
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumLyricModeTest {
    @Test fun `full landscape preserves lyrics across rotation live settings and side return`() {
        val settings = object : IslandSettings {
            override val threeIslands = true
            override var landscapeFull = true
            override val landscapeMore = true
        }
        val host = InMemoryIslandHost()
        val owner = Any()
        val media = NativeMusicSpectrumTest.Content(NativeMusicSpectrumTest.Template(
            NativeMusicSpectrumTest.Area(
                NativeMusicSpectrumTest.Info(1, NativeMusicSpectrumTest.Pic("miui_media_album_icon", 1)),
                NativeMusicSpectrumTest.Info(2, NativeMusicSpectrumTest.Pic("musicWave", 2)),
            ),
        ))
        host.bind(owner, IslandSlot.BIG, Any()).bind(owner, IslandSlot.SMALL, media)
        var rebuilding = false
        var temporary = false
        val ports = object : IslandHostPorts by host {
            override fun inConfigRebuild(owner: Any) = rebuilding
            override fun showOnce(owner: Any): Any? = if (temporary) media else null
        }
        val runtime = IslandRuntime(ports, settings, { false }, NativeMusicSpectrum(media.javaClass), null)
        val session = runtime.sessions.getOrCreate(owner)
        fun guarded() = runtime.spectrum.sideLyricTarget(session, media)

        assertTrue(guarded()) // A saved landscape setting does not disable portrait protection.
        host.orientation = IslandOrientation.LANDSCAPE
        assertFalse(guarded())
        rebuilding = true
        assertFalse(runtime.landscapeFullEnabled(session)) // Geometry is temporarily ineligible.
        assertFalse(guarded()) // The requested lyric mode must survive the rebuild.
        rebuilding = false
        temporary = true
        assertFalse(guarded()) // App/show-once handoff must not suppress full-template text.
        temporary = false
        session.scene.musicSmall = media // Stale admission from portrait must not suppress lyrics.
        assertFalse(guarded())
        host.bind(owner, IslandSlot.SMALL, null)
        session.slot.extras = listOf(media) // The extra/return renderer obeys the same mode gate.
        assertFalse(guarded())
        settings.landscapeFull = false
        assertTrue(guarded()) // Landscape-more alone still uses compact side templates.
        settings.landscapeFull = true
        assertFalse(guarded())
        host.orientation = IslandOrientation.PORTRAIT
        assertTrue(guarded())
        host.bind(owner, IslandSlot.BIG, media)
        assertFalse(guarded())
        host.bind(owner, IslandSlot.BIG, Any()).bind(owner, IslandSlot.EXPANDED, media)
        assertFalse(guarded())
    }
}
