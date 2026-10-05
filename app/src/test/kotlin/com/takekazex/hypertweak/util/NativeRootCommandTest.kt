package com.takekazex.hypertweak.util

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class NativeRootCommandTest {
    @Test fun `root grant or pipe timeout retires the exact process`() {
        val process = ProcessBuilder("sh", "-c", "exec sleep 30").start()
        assertThrows(TimeoutException::class.java) {
            NativeRootCommand.run("unused", timeoutMs = 250, start = { process })
        }
        assertTrue(process.waitFor(2, TimeUnit.SECONDS)); assertFalse(process.isAlive)
    }
    @Test fun `binary limit rejects oversized output without retaining a reader`() {
        val process = ProcessBuilder("sh", "-c", "trap '' TERM; printf 123456789; exec sleep 30").start()
        assertThrows(ExecutionException::class.java) {
            NativeRootCommand.run("unused", maxBytes = 4, start = { process })
        }
        assertTrue(process.waitFor(2, TimeUnit.SECONDS))
        assertFalse(process.isAlive)
        val next = ProcessBuilder("sh", "-c", "printf ok").start()
        assertEquals("ok", NativeRootCommand.run("unused", start = { next }).toString(Charsets.UTF_8))
    }
    @Test fun `exit rejection is never reported as successful root output`() {
        val process = ProcessBuilder("sh", "-c", "exit 19").start()
        val failure = assertThrows(ExecutionException::class.java) {
            NativeRootCommand.run("unused", start = { process })
        }
        assertTrue(failure.cause?.message?.contains("19") == true)
    }
    @Test fun `quoted paths cannot evaluate shell substitutions or newline commands`() {
        val value = "odd'\n${'$'}(printf bad)`printf bad`"
        val process = ProcessBuilder("sh", "-c", "printf %s " + NativeRootCommand.quote(value)).start()
        assertEquals(value, NativeRootCommand.run("unused", start = { process }).toString(Charsets.UTF_8))
    }
}
