package com.takekazex.hypertweak.hook.rules.systemui.island

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IslandCondensedFontSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun config(fonts: String): File = temporary.newFile().apply {
        writeText("<fonts-modification><family name=\"misans-condensed\">$fonts</family></fonts-modification>")
    }

    @Test fun `resolve native resource without depending on the process family alias map`() {
        val resource = temporary.newFile("NativeCondensed.ttf")
        assertEquals(resource.canonicalFile, IslandCondensedFontSource.resolve(
            config("<font weight=\"400\" supportedAxes=\"wght,wdth\">NativeCondensed.ttf</font>"), temporary.root))
    }

    @Test fun `current device native configuration resolves its installed font resource`() {
        val directory = System.getenv("ISLAND_FONT_DIRECTORY")?.let(::File)
        assumeTrue(directory?.isDirectory == true)
        val root = requireNotNull(directory)
        val font = IslandCondensedFontSource.resolve(File(root, "mi_fonts_customization.xml"), root)
        assertEquals("MiSansCondensed2T.ttf", font.name)
        assertEquals(172548L, font.length())
    }

    @Test fun `ambiguous family font resources fail closed`() {
        assertThrows(IllegalStateException::class.java) {
            IslandCondensedFontSource.resolve(config("<font>one.ttf</font><font>two.ttf</font>"), temporary.root)
        }
    }

    @Test fun `missing resources cannot silently fall back to the default font`() {
        assertThrows(IllegalStateException::class.java) {
            IslandCondensedFontSource.resolve(config("<font>missing.ttf</font>"), temporary.root)
        }
    }

    @Test fun `configuration cannot escape its native font directory`() {
        assertThrows(IllegalStateException::class.java) {
            IslandCondensedFontSource.resolve(config("<font>../other.ttf</font>"), temporary.root)
        }
    }
}
