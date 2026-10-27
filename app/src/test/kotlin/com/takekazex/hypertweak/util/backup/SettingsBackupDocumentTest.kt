package com.takekazex.hypertweak.util.backup

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupDocumentTest {
    @Test fun readsBomAndUnicodeWithoutReplacingMalformedUtf8() {
        val json = "{\"text\":\"中文 🌟\"}"
        assertEquals(json, SettingsBackupDocument.read(ByteArrayInputStream(("\uFEFF" + json).toByteArray())))
        assertThrows(CharacterCodingException::class.java) {
            SettingsBackupDocument.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
    }

    @Test fun unknownLengthStreamIsStoppedAtLimitPlusOneWithoutClosingIt() {
        var consumed = 0
        var closed = false
        val endless = object : InputStream() {
            override fun read(): Int { consumed++; return 32 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(32, offset, offset + length)
                consumed += length
                return length
            }
            override fun close() { closed = true }
        }
        assertThrows(IllegalArgumentException::class.java) { SettingsBackupDocument.read(endless) }
        assertEquals(SettingsBackupDocument.MAX_BYTES + 1, consumed)
        assertFalse(closed)
        val exact = " ".repeat(SettingsBackupDocument.MAX_BYTES)
        assertEquals(exact, SettingsBackupDocument.read(ByteArrayInputStream(exact.toByteArray())))
    }

    @Test fun directImportLimitsUtf8BytesAndDepthBeforeJsonParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupDocument.validate("中".repeat(SettingsBackupDocument.MAX_BYTES / 3 + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupDocument.validate("[".repeat(33) + "]".repeat(33))
        }
        val valid = "{\"text\":\"${"[".repeat(100)} \\\" \\\\ / # '\"}"
        assertEquals(valid, SettingsBackupDocument.validate(valid))
        for (bad in listOf("{}\u0000{}", "{", "{\"a\":\"unfinished}", "/*x*/{}", "{'a':1}", "#x\n{}")) {
            assertThrows("Must reject $bad", IllegalArgumentException::class.java) { SettingsBackupDocument.validate(bad) }
        }
    }
}
