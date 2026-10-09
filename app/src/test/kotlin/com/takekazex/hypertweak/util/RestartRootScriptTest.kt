package com.takekazex.hypertweak.util

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** The production command targets only the fake procfs created by each test. */
class RestartRootScriptTest {
    private fun run(replacePidBeforeKill: Boolean = false): Pair<Int, String> {
        val root = Files.createTempDirectory("restart-script").toFile()
        try {
            val proc = File(root, "proc").apply { mkdirs() }
            File(proc, "123").mkdirs()
            File(proc, "123/stat").writeText("123 (systemui worker) S " + List(18) { "0" }.joinToString(" ") + " 50 0\n")
            File(root, "pid").writeText("123\n")
            val bin = File(root, "bin").apply { mkdirs() }
            fun command(name: String, body: String) {
                File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
            }
            command("pidof", "count=\$(cat '${root.path}/pidof-count' 2>/dev/null || echo 0); count=\$((count + 1)); echo \$count > '${root.path}/pidof-count'; " +
                if (replacePidBeforeKill) "if [ \$count -ge 2 ]; then echo 456; else cat '${root.path}/pid'; fi" else "cat '${root.path}/pid'")
            command("record_kill", "echo \"\$*\" >> '${root.path}/kills'\necho 456 > '${root.path}/pid'")
            command("sleep", "touch '${root.path}/slept'")
            val script = RestartRootScript.SYSTEM_UI
                .replace("/proc/", "${proc.path}/")
                .replace("kill -9", "record_kill -9")
            val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).apply {
                environment()["PATH"] = "${bin.path}:${System.getenv("PATH")}"
            }.start()
            assertTrue("immediate restart command must finish promptly", process.waitFor(5, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            val kills = File(root, "kills").takeIf { it.exists() }?.readText().orEmpty()
            assertFalse("no delay before the kill", File(root, "slept").exists())
            if (replacePidBeforeKill) assertEquals("", kills) else assertEquals("-9 123\n", kills)
            return process.exitValue() to output
        } finally { root.deleteRecursively() }
    }

    @Test fun `SystemUI restart kills the current PID once and immediately confirms replacement`() {
        val (exit, output) = run()
        assertEquals(0, exit)
        assertTrue(output.contains("HT_RESTART_KILLED pid=123"))
        assertTrue(output.contains("HT_RESTART_STARTED pid=456"))
        assertTrue(output.contains("HT_RESTART_OK"))
        assertFalse(output.contains("HT_RESTART_WAIT"))
    }

    @Test fun `replacement before dispatch is never killed`() {
        assertEquals(2, run(replacePidBeforeKill = true).first)
    }

    @Test fun `shell metacharacters are rejected before Root`() {
        assertThrows(IllegalArgumentException::class.java) { RestartRootScript.forPackage("com.example;reboot") }
    }
}
