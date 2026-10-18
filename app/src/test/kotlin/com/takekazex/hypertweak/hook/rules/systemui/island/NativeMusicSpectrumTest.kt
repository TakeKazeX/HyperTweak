package com.takekazex.hypertweak.hook.rules.systemui.island
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMusicSpectrum

import org.junit.Assert.*
import org.junit.Test

class NativeMusicSpectrumTest {
    class Pic(val pic: String, val type: Int?)
    class TextInfo(val title: String? = null, val content: String? = null, val frontTitle: String? = null)
    class Info(val type: Int?, val picInfo: Pic, val textInfo: TextInfo? = null, val progressInfo: String? = null)
    class Area(val imageTextInfoLeft: Info, val imageTextInfoRight: Info,
        val textInfo: TextInfo? = null, val progressTextInfo: String? = null,
        val fixedWidthDigitInfo: String? = null, val sameWidthDigitInfo: String? = null)
    class Template(val bigIslandArea: Area)
    class Content(val template: Template, val media: Boolean = true) { fun isMediaApp(): Boolean? = media }
    private fun content(wave: String = "musicWave", lyric: String? = null, media: Boolean = true) =
        Content(Template(Area(Info(1, Pic("miui_media_album_icon", 1)), Info(2, Pic(wave, 2)), textInfo = lyric?.let { TextInfo(title = it) })), media)

    @Test fun `empty text configuration objects do not masquerade as lyric modules`() {
        val matcher = NativeMusicSpectrum(Content::class.java)
        val cover = Info(1, Pic("miui_media_album_icon", 1), TextInfo())
        val wave = Info(2, Pic("musicWave", 2), TextInfo(title = "", content = " "))
        assertTrue(matcher.matches(Content(Template(Area(cover, wave, TextInfo())))))
        val lyric = Info(2, Pic("musicWave", 2), TextInfo(frontTitle = "lyrics"))
        assertFalse(matcher.matches(Content(Template(Area(cover, lyric)))))
    }

    @Test fun `only native media cover and playing or paused spectrum match`() {
        val matcher = NativeMusicSpectrum(Content::class.java)
        assertTrue(matcher.matches(content()))
        assertTrue(matcher.matches(content("musicPause")))
        assertFalse(matcher.matches(content("cast_device")))
        assertFalse(matcher.matches(content(lyric = "lyric module")))
        assertFalse(matcher.matches(content(media = false)))
    }
}
