package com.takekazex.hypertweak.util

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DownloadXlStorageTest {
    @Test fun `root absence is cross checked but scoped denial cannot prove absence`() {
        assertEquals(true, DownloadXlStorage.resolvePresence("ABSENT", true))
        assertEquals(true, DownloadXlStorage.resolvePresence(null, true))
        assertEquals(false, DownloadXlStorage.resolvePresence("ABSENT", false))
        assertNull(DownloadXlStorage.resolvePresence(null, false))
    }

    @Test fun `scripts inspect and delete both exact aliases preserving siblings`() {
        val root = Files.createTempDirectory("xl-cleanup").toFile()
        try {
            val primary = root.resolve("primary").apply { mkdir() }
            val alias = root.resolve("alias ' quote").apply { mkdir() }
            val sibling = root.resolve("alias ' quote-other").apply { mkdir() }
            alias.resolve("dp.log").writeText("log")
            val paths = listOf(primary.path, alias.path)
            fun run(script: String): String {
                val process = ProcessBuilder("sh", "-c", script).start()
                val output = process.inputStream.bufferedReader().readText().trim()
                assertEquals(0, process.waitFor())
                return output
            }
            assertEquals("PRESENT", run(DownloadXlStorage.existenceScript(paths)))
            primary.delete()
            assertEquals("PRESENT", run(DownloadXlStorage.existenceScript(paths)))
            assertEquals("DELETED", run(DownloadXlStorage.deletionScript(paths)))
            assertFalse(alias.exists())
            assertTrue(sibling.isDirectory)
            assertEquals("ABSENT", run(DownloadXlStorage.existenceScript(paths)))
            assertEquals("DELETED", run(DownloadXlStorage.deletionScript(paths)))
        } finally { root.deleteRecursively() }
    }
}
