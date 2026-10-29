package com.takekazex.hypertweak.util.platform

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AivsDirectoryMigrationTest {
    private val pkg = "com.miui.voiceassist"
    private fun withRoot(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("aivs-migration-")
        try { test(root) } finally { root.toFile().deleteRecursively() }
    }
    private fun run(root: Path, move: Boolean): Pair<Int, String> {
        // Execute the production script against a disposable fixture without requiring host root.
        val script = AivsDirectoryMigration.script("/storage/emulated/0", move)
            .replace("/storage/emulated/0", root.toString())
            .replace("[ \"${'$'}(id -u)\" = 0 ] || exit 60", ":")
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }
    @Test fun `query is read only and migration merges nested and hidden files`() = withRoot { root ->
        val old = Files.createDirectories(root.resolve("$pkg/audio"))
        Files.writeString(old.resolve(".hidden"), "audio")
        val target = Files.createDirectories(root.resolve("MIUI/AIVS/$pkg/audio"))
        Files.writeString(target.resolve("keep"), "native")
        assertEquals(0, run(root, false).first)
        assertTrue(Files.exists(old.resolve(".hidden")))
        assertEquals(0, run(root, true).first)
        assertFalse(Files.exists(root.resolve(pkg)))
        assertEquals("audio", Files.readString(target.resolve(".hidden")))
        assertEquals("native", Files.readString(target.resolve("keep")))
        assertEquals(0, run(root, true).first)
    }
    @Test fun `conflict preflight preserves all source files`() = withRoot { root ->
        val old = Files.createDirectories(root.resolve("$pkg/audio"))
        val target = Files.createDirectories(root.resolve("MIUI/AIVS/$pkg/audio"))
        Files.writeString(old.resolve("a"), "unmoved")
        Files.writeString(old.resolve("z"), "source")
        Files.writeString(target.resolve("z"), "destination")
        assertNotEquals(0, run(root, true).first)
        assertEquals("unmoved", Files.readString(old.resolve("a")))
        assertEquals("source", Files.readString(old.resolve("z")))
        assertEquals("destination", Files.readString(target.resolve("z")))
    }
    @Test fun `symlinks and files used as package directories are rejected`() = withRoot { root ->
        Files.createSymbolicLink(root.resolve(pkg), root.resolve("outside"))
        assertNotEquals(0, run(root, false).first)
        assertNotEquals(0, run(root, true).first)
        Files.delete(root.resolve(pkg))
        Files.writeString(root.resolve(pkg), "keep")
        assertNotEquals(0, run(root, true).first)
        assertEquals("keep", Files.readString(root.resolve(pkg)))
    }
    @Test fun `destination symlink and later package conflict reject the whole batch`() = withRoot { root ->
        val old = Files.createDirectories(root.resolve("$pkg/audio"))
        Files.writeString(old.resolve("record"), "keep")
        val target = Files.createDirectories(root.resolve("MIUI/AIVS/$pkg"))
        Files.createSymbolicLink(target.resolve("audio"), root.resolve("outside"))
        assertNotEquals(0, run(root, true).first)
        assertTrue(Files.exists(old.resolve("record")))
        Files.delete(target.resolve("audio"))
        val other = "com.xiaomi.finddevice/audio"
        val otherSource = Files.createDirectories(root.resolve(other))
        Files.writeString(otherSource.resolve("conflict"), "source")
        val otherTarget = Files.createDirectories(root.resolve("MIUI/AIVS/$other"))
        Files.writeString(otherTarget.resolve("conflict"), "destination")
        assertNotEquals(0, run(root, true).first)
        assertTrue(Files.exists(old.resolve("record")))
    }
    @Test fun `unsupported and injected storage roots are rejected`() {
        for (root in listOf("/sdcard", "/storage/emulated/0/..", "/storage/emulated/0;id")) {
            assertThrows(IllegalArgumentException::class.java) { AivsDirectoryMigration.script(root, true) }
        }
    }
}
