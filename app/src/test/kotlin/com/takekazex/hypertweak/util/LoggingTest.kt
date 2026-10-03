package com.takekazex.hypertweak.util

import org.junit.Assert.*
import org.junit.Test

class LoggingTest {
    private fun record(level: String = "W", stack: String = "boom") = LogRecord(
        "2026-10-03 11:05:36.350", level, "3519", "Feature", "FAILED", "operation failed",
        stack, "system", "LSPosed"
    )
    private fun head(module: String = "com.takekazex.hypertweak", id: String = "id", part: String = "0",
                     text: String = "Feature: operation failed", level: String = "E") =
        "[ 2026-10-03T11:05:36.350     1000:  3519:  4851 $level/LSPosedFramework ] (system)[$module,HyperTweak,$id,$part,1] $text"

    @Test fun `wire format round trips paths newlines separators and provenance`() {
        val r = record().copy(message = "C:\\new\\file\nnext\r\u001f", stack = "at first\nCaused by: boom")
        assertEquals(r.copy(message = r.message.replace("\u001f", " ")), LogCodec.decode(LogCodec.encode(r)))
    }
    @Test fun `hook failure event remains specific when a throwable exists`() {
        assertEquals("HOOK_FAILED", LogCodec.event("HOOK_FAILED target=sample", true))
    }
    @Test fun `debug skips and successful error interception are not issues`() {
        val r = record("D").copy(event = "HOOK_SKIPPED", stack = "")
        assertFalse(r.isError); assertFalse(r.isWarning)
        assertFalse(record("I").copy(message = "error dispatch forced to success").isError)
        assertFalse(record("W").isError)
    }
    @Test fun `malformed and metadata lines do not become an exception stack`() {
        val r = record()
        val parsed = LogCodec.parse("# export\n${LogCodec.encode(r)}\nLSPosed=FAILED\nv3\u001fbroken")
        assertEquals(listOf(r), parsed)
    }
    @Test fun `old v2 records remain readable`() {
        val line = listOf("v2", "10-03 11:05:36.350", "E", "3519", "Feature", "FAILED", "message", "stack").joinToString(LogCodec.SEPARATOR)
        assertEquals("message", LogCodec.decode(line)?.message)
        assertNull(LogCodec.decode("v3\u001fbroken"))
    }
    @Test fun `tail preserves complete records and bounds a huge single record`() {
        assertEquals("third", LogCodec.boundedTail("first\nsecond\nthird", 7))
        assertEquals("", LogCodec.boundedTail("a".repeat(30), 5))
    }
    @Test fun `framework selection uses module identity and retains one exception with its stack`() {
        val lines = listOf(head(), "java.lang.IllegalStateException: boom", "\tat host.Frame.run(Frame.java:1)",
            head("other.module", text = "mentions com.takekazex.hypertweak"), "\tat other.Frame.run(Frame.java:2)")
        val records = LsposedLogParser.parse(lines.asSequence())
        assertEquals(1, records.size)
        assertTrue(records.single().stack.contains("host.Frame"))
        assertFalse(records.single().stack.contains("other.Frame"))
        assertEquals("system", records.single().process)
    }
    @Test fun `split framework message joins by message identity`() {
        val records = LsposedLogParser.parse(sequenceOf(head(), "Exception: boom",
            head(part = "1", text = "\tat host.Second.run(Second.java:2)"),
            head(id = "different", part = "1", text = "\tat unrelated.Frame.run(Unknown.java:3)")))
        assertEquals(1, records.size)
        assertTrue(records.single().stack.contains("host.Second"))
        assertFalse(records.single().stack.contains("unrelated"))
    }
    @Test fun `interleaved foreign messages cannot break or contaminate a multipart stack`() {
        val records = LsposedLogParser.parse(sequenceOf(head(), "Exception: boom",
            head("other.module", id = "foreign", text = "foreign message"), "foreign stack",
            head(part = "1", text = "\tat host.Second.run(Second.java:2)")))
        assertEquals(1, records.size)
        assertTrue(records.single().stack.contains("host.Second"))
        assertFalse(records.single().stack.contains("foreign"))
    }
    @Test fun `orphan stack tail and partial read head do not become new failures`() {
        assertTrue(LsposedLogParser.parse(sequenceOf("\tat clipped.Frame.run()", head(part = "1"))).isEmpty())
    }
    @Test fun `framework retention keeps the newest bounded record set`() {
        val records = LsposedLogParser.parse((0..2100).asSequence().map { head(id = "$it", text = "Feature: number=$it") })
        assertEquals(2000, records.size)
        assertEquals("number=101", records.first().message)
        assertEquals("number=2100", records.last().message)
    }
    @Test fun `repeats retain first failure and report exact additional count`() {
        val limiter = LogRepeatLimiter(windowMs = 100)
        assertTrue(limiter.accept(record(), 0))
        repeat(9) { assertFalse(limiter.accept(record(), 1)) }
        assertTrue(limiter.drain(99).isEmpty())
        val summary = limiter.drain(100).single()
        assertTrue(summary.message.contains("count=9")); assertEquals("W", summary.level)
        assertEquals("REPEATED", summary.event); assertEquals("", summary.stack)
        assertTrue(limiter.accept(record(), 101))
    }
    @Test fun `different exception stacks and levels cannot suppress each other`() {
        val limiter = LogRepeatLimiter()
        assertTrue(limiter.accept(record(), 0))
        assertTrue(limiter.accept(record(stack = "different root cause"), 0))
        assertTrue(limiter.accept(record("E"), 0))
    }
    @Test fun `full suppression table never hides a new issue and forced drain preserves counts`() {
        val limiter = LogRepeatLimiter(capacity = 1)
        assertTrue(limiter.accept(record(), 0))
        assertFalse(limiter.accept(record(), 1))
        assertTrue(limiter.accept(record(stack = "new issue"), 2))
        assertEquals(1, limiter.drain(2, force = true).size)
    }
}
