package com.takekazex.hypertweak.util

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LsposedLogReaderTest {
    private fun read(script: String, timeout: Long = 3000) = LsposedLogReader.read(timeout) {
        ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
    }

    @Test fun `missing root executable is an explicit availability state`() {
        assertEquals(LsposedLogReader.Status.ROOT_UNAVAILABLE,
            LsposedLogReader.read(3000) { throw IOException("not installed") }.status)
    }
    @Test fun `root wrapper exit zero alone is not proof of file access`() {
        assertEquals(LsposedLogReader.Status.ROOT_UNAVAILABLE, read("echo denied; exit 0").status)
    }
    @Test fun `a readable file with no module entries is a valid empty source`() {
        val result = read("echo '----HyperTweak log file----'; exit 0")
        assertEquals(LsposedLogReader.Status.READY, result.status)
        assertTrue(result.records.isEmpty())
    }
    @Test fun `file absence and failed file reads stay distinguishable`() {
        assertEquals(LsposedLogReader.Status.NO_FILES, read("exit 42").status)
        assertEquals(LsposedLogReader.Status.FAILED, read("exit 43").status)
    }
    @Test fun `a child holding stdout open cannot hold the caller beyond its deadline`() {
        val start = System.nanoTime()
        val result = read("sleep 0.5", timeout = 100)
        assertEquals(LsposedLogReader.Status.TIMED_OUT, result.status)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
    }
}
