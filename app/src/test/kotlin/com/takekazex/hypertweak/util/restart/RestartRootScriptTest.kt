package com.takekazex.hypertweak.util.restart

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** The production command targets only the fake procfs created by each test. */
class RestartRootScriptTest {
    private data class Proc(val pid: Int, val uid: Int, val ticks: Int = 50)

    private data class Result(val exit: Int, val output: String, val kills: List<String>, val slept: Boolean)

    /**
     * [initial] is what `pidof` reports at dispatch (in that order); [afterKill] replaces it once
     * the first kill is recorded. [listedAtRecheck] replaces it from the dispatch-time recheck on.
     */
    private fun run(
        processes: List<Proc>,
        initial: String,
        afterKill: String,
        listedAtRecheck: String? = null,
    ): Result {
        val root = Files.createTempDirectory("restart-script").toFile()
        try {
            val proc = File(root, "proc").apply { mkdirs() }
            for (p in processes) {
                File(proc, "${p.pid}").mkdirs()
                File(proc, "${p.pid}/stat").writeText(
                    "${p.pid} (systemui worker) S " + List(18) { "0" }.joinToString(" ") + " ${p.ticks} 0\n"
                )
                File(proc, "${p.pid}/status").writeText(
                    "Name:\tcom.android.systemui\nPPid:\t1973\nUid:\t${p.uid}\t${p.uid}\t${p.uid}\t${p.uid}\n"
                )
            }
            File(root, "pid").writeText("$initial\n")
            val bin = File(root, "bin").apply { mkdirs() }
            fun command(name: String, body: String) {
                File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
            }
            val recheck = listedAtRecheck?.let {
                "if [ \$count -ge 3 ] && [ ! -e '${root.path}/kills' ]; then echo '$it'; exit 0; fi; "
            }.orEmpty()
            command(
                "pidof",
                "count=\$(cat '${root.path}/pidof-count' 2>/dev/null || echo 0); count=\$((count + 1)); " +
                    "echo \$count > '${root.path}/pidof-count'; $recheck" +
                    "out=\$(cat '${root.path}/pid'); [ -n \"\$out\" ] || exit 1; echo \"\$out\"",
            )
            command("record_kill", "echo \"\$*\" >> '${root.path}/kills'\necho '$afterKill' > '${root.path}/pid'")
            command("sleep", "touch '${root.path}/slept'")
            val script = RestartRootScript.SYSTEM_UI
                .replace("/proc/", "${proc.path}/")
                .replace("kill -9", "record_kill -9")
            val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).apply {
                environment()["PATH"] = "${bin.path}:${System.getenv("PATH")}"
            }.start()
            assertTrue("immediate restart command must finish promptly", process.waitFor(5, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            val kills = File(root, "kills").takeIf { it.exists() }?.readLines().orEmpty()
            return Result(process.exitValue(), output, kills, File(root, "slept").exists())
        } finally { root.deleteRecursively() }
    }

    private val primary = Proc(123, uid = 10226)
    private val replacement = Proc(456, uid = 10226)

    @Test fun `SystemUI restart kills the current PID once and immediately confirms replacement`() {
        val result = run(listOf(primary, replacement), initial = "123", afterKill = "456")
        assertEquals(0, result.exit)
        assertEquals(listOf("-9 123"), result.kills)
        assertFalse("no delay before the kill", result.slept)
        assertTrue(result.output.contains("HT_RESTART_KILLED pid=123"))
        assertTrue(result.output.contains("HT_RESTART_STARTED pid=456"))
        assertTrue(result.output.contains("HT_RESTART_OK"))
    }

    @Test fun `replacement before dispatch is never killed`() {
        val result = run(listOf(primary, replacement), initial = "123", afterKill = "456", listedAtRecheck = "456")
        assertEquals(2, result.exit)
        assertEquals(emptyList<String>(), result.kills)
    }

    @Test fun `every listed SystemUI instance is restarted and pidof order does not matter`() {
        // Field report: `pidof` returned "15665 6728" and the script rejected the whole list.
        val secondary = Proc(15665, uid = 99910226)
        val main = Proc(6728, uid = 10226)
        val result = run(listOf(main, secondary, replacement), initial = "15665 6728", afterKill = "456")
        assertEquals(0, result.exit)
        assertEquals(listOf("-9 6728", "-9 15665"), result.kills)
        assertTrue(result.output.contains("HT_RESTART_STARTED pid=456"))
        assertTrue(result.output.contains("HT_RESTART_OK"))
    }

    @Test fun `a lingering killed instance does not hide the user-0 replacement`() {
        val stale = Proc(6728, uid = 10226)
        val current = Proc(15665, uid = 10226, ticks = 90)
        val result = run(listOf(stale, current, replacement), initial = "6728 15665", afterKill = "456 6728")
        assertEquals(0, result.exit)
        assertTrue(result.output.contains("HT_RESTART_STARTED pid=456"))
    }

    @Test fun `only a secondary-user replacement is not reported as a restart`() {
        val secondaryReplacement = Proc(456, uid = 99910226)
        val result = run(listOf(primary, secondaryReplacement), initial = "123", afterKill = "456")
        assertEquals(4, result.exit)
        assertFalse(result.output.contains("HT_RESTART_OK"))
    }

    @Test fun `shell metacharacters are rejected before Root`() {
        assertThrows(IllegalArgumentException::class.java) { RestartRootScript.forPackage("com.example;reboot") }
    }
}
